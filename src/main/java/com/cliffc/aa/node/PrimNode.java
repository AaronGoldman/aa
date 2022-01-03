package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;

import static com.cliffc.aa.AA.*;

// Primitives can be used as an internal operator (their apply() call does the
// primitive operation).  Primitives are wrapped as functions when returned
// from Env lookup, although the immediate lookup+apply is optimized to just
// make a new primitive.  See FunNode for function Node structure.
//
// Fun/Parm-per-arg/Prim/Ret
//
public abstract class PrimNode extends Node {
  public final String _name;    // Unique name (and program bits)
  public final TypeFunPtr _tfp; // FIDX, nargs, display argument, return type
  public final TypeStruct _formals;
  Parse[] _badargs;             // Filled in when inlined in CallNode
  public boolean _thunk_rhs;    // Thunk (delay) right-hand-argument.
  public PrimNode( String name, TypeStruct formals, Type ret ) {
    super(OP_PRIM);
    _name = name;
    int fidx = BitsFun.new_fidx();
    _formals = formals;
    _tfp=TypeFunPtr.make(BitsFun.make0(fidx),formals.nargs(),TypeMemPtr.NO_DISP,ret);
    _badargs=null;
    _thunk_rhs=false;
  }

  private static PrimNode[] PRIMS = null; // All primitives

  // TODO: Just build precedence table for ops; need a lookup that includes prec order, nargs, placement
  public static PrimNode[] PRIMS() {
    if( PRIMS!=null ) return PRIMS;

    // Binary-operator primitives, sorted by precedence.
    PrimNode[][] PRECEDENCE = new PrimNode[][]{

      {new MulF64(), new DivF64(), new MulI64(), new DivI64(), new ModI64(), },

      {new AddF64(), new SubF64(), new AddI64(), new SubI64() },

      {new LT_F64(), new LE_F64(), new GT_F64(), new GE_F64(),
       new LT_I64(), new LE_I64(), new GT_I64(), new GE_I64(),},

      {new EQ_F64(), new NE_F64(), new EQ_I64(), new NE_I64(), new EQ_OOP(), new NE_OOP(), },

      {new AndI64(), },

      {new OrI64(), },

      //{new AndThen(), },
      //
      //{new OrElse(), },

    };

    // Other primitives, not binary operators
    PrimNode[] others = new PrimNode[] {
      // These are called like a function, so do not have a precedence
      new RandI64(),

      new ConvertI64F64(),

      //// These are balanced-ops, called by Parse.term()
      //new MemPrimNode.ReadPrimNode.LValueRead  (), // Read  an L-Value: (ary,idx) ==> elem
      //new MemPrimNode.ReadPrimNode.LValueWrite (), // Write an L-Value: (ary,idx,elem) ==> elem
      //new MemPrimNode.ReadPrimNode.LValueWriteFinal(), // Final Write an L-Value: (ary,idx,elem) ==> elem
      
      // These are unary ops, precedence determined outside 'Parse.expr'
      //new MemPrimNode.ReadPrimNode.LValueLength(), // The other array ops are "balanced ops" and use term() for precedence
      new MinusF64(),
      new MinusI64(),
      new Not(),
    };

    Ary<PrimNode> allprims = new Ary<>(others);
    for( PrimNode prim : others ) allprims.push(prim);
    for( PrimNode[] prims : PRECEDENCE )
      for( PrimNode prim : prims )
        allprims.push(prim);
    PRIMS = allprims.asAry();

    return PRIMS;
  }

  // All primitives are effectively H-M Applies with a hidden internal Lambda.
  @Override public boolean unify( boolean test ) {
    boolean progress = false;
    for( TypeFld fld : _formals )
      progress |= prim_unify(tvar(fld._order),fld._t,test);
    progress |= prim_unify(tvar(),_tfp._ret,test);
    return progress;
  }
  private boolean prim_unify(TV2 arg, Type t, boolean test) {
    if( arg.is_base() && t.isa(arg._flow) ) return false;
    if( arg.is_err() ) return false;
    if( test ) return true;
    return arg.unify(TV2.make_base(this, arg._flow==null ? t : t.meet(arg._flow), "Prim_unify"),test);
  }


  // Apply types are 1-based (same as the incoming node index), and not
  // zero-based (not same as the _formals and _args fields).
  public abstract Type apply( Type[] args ); // Execute primitive
  // Pretty print short primitive signature based on first argument:
  //  + :{int int -> int }  ==>>   + :int
  //  + :{flt flt -> flt }  ==>>   + :flt
  //  + :{str str -> str }  ==>>   + :str
  // str:{int     -> str }  ==>>  str:int
  // str:{flt     -> str }  ==>>  str:flt
  // == :{ptr ptr -> int1}  ==>>  == :ptr
  @Override public String xstr() { return _name+":"+_formals.get("^")._t; }
  @Override public Type value() {
    if( is_keep() ) return Type.ALL;
    Type[] ts = Types.get(_defs._len); // 1-based
    // If all inputs are constants we constant-fold.  If any input is high, we
    // return high otherwise we return low.
    boolean is_con = true, has_high = false;
    for( TypeFld fld : _formals ) {
      Type tactual = ts[fld._order] = val(fld._order);
      Type tformal = fld._t;
      Type t = tformal.dual().meet(tactual);
      if( !t.is_con() ) {
        is_con = false;         // Some non-constant
        if( t.above_center() ) has_high=true;
      }
    }
    Type rez = _tfp._ret;
    Type rez2 = is_con ? apply(ts) : (has_high ? rez.dual() : rez);
    Types.free(ts);
    return rez2;
  }

  @Override public Node ideal_reduce() {
    if( _live != TypeMem.DEAD ) return null;
    Node progress=null;
    for( int i=DSP_IDX; i<_defs._len; i++ )
      if( in(i)!=Env.ANY ) progress=set_def(i,Env.ANY);
    return progress;
  }


  @Override public ErrMsg err( boolean fast ) {
    for( TypeFld fld : _formals ) {
      Type tactual = val(fld._order);
      Type tformal = fld._t;
      if( !tactual.isa(tformal) )
        return _badargs==null ? ErrMsg.BADARGS : ErrMsg.typerr(_badargs[fld._order-DSP_IDX],tactual,null,tformal);
    }
    return null;
  }
  // Prims are equal for same-name-same-signature (and same inputs).
  // E.g. float-minus of x and y is NOT the same as int-minus of x and y
  // despite both names being '-'.
  @Override public int hashCode() { return super.hashCode()+_name.hashCode()+_formals._hash; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof PrimNode) ) return false;
    PrimNode p = (PrimNode)o;
    return _name.equals(p._name) && _formals==p._formals;
  }

  public static class ConvertI64F64 extends PrimNode {
    public ConvertI64F64() { super("flt64",TypeStruct.INT64,TypeFlt.FLT64); }
    @Override public Type apply( Type[] args ) { return TypeFlt.con((double)args[DSP_IDX].getl()); }
  }

  // 1Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim1OpF64 extends PrimNode {
    Prim1OpF64( String name ) { super(name,TypeStruct.FLT64,TypeFlt.FLT64); }
    public Type apply( Type[] args ) { return TypeFlt.con(op(args[DSP_IDX].getd())); }
    abstract double op( double d );
  }

  static class MinusF64 extends Prim1OpF64 {
    MinusF64() { super("-"); }
    @Override double op( double d ) { return -d; }
  }

  // 1Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim1OpI64 extends PrimNode {
    Prim1OpI64( String name ) { super(name,TypeStruct.INT64,TypeInt.INT64); }
    @Override public Type apply( Type[] args ) { return TypeInt.con(op(args[DSP_IDX].getl())); }
    abstract long op( long d );
  }

  public static class MinusI64 extends Prim1OpI64 {
    public MinusI64() { super("-"); }
    @Override long op( long x ) { return -x; }
  }

  // 2Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim2OpF64 extends PrimNode {
    Prim2OpF64( String name ) { super(name,TypeStruct.FLT64_FLT64,TypeFlt.FLT64); }
    @Override public Type apply( Type[] args ) { return TypeFlt.con(op(args[DSP_IDX].getd(),args[ARG_IDX].getd())); }
    abstract double op( double x, double y );
  }

  public static class AddF64 extends Prim2OpF64 {
    public AddF64() { super("+"); }
    double op( double l, double r ) { return l+r; }
  }

  public static class SubF64 extends Prim2OpF64 {
    public SubF64() { super("-"); }
    double op( double l, double r ) { return l-r; }
  }

  public static class MulF64 extends Prim2OpF64 {
    public MulF64() { super("*"); }
    @Override double op( double l, double r ) { return l*r; }
  }

  public static class DivF64 extends Prim2OpF64 {
    public DivF64() { super("/"); }
    @Override double op( double l, double r ) { return l/r; }
  }

  // 2RelOps have uniform input types, and bool output
  abstract static class Prim2RelOpF64 extends PrimNode {
    Prim2RelOpF64( String name ) { super(name,TypeStruct.FLT64_FLT64,TypeInt.BOOL); }
    @Override public Type apply( Type[] args ) { return op(args[DSP_IDX].getd(),args[ARG_IDX].getd())?TypeInt.TRUE:TypeInt.FALSE; }
    abstract boolean op( double x, double y );
  }

  public static class LT_F64 extends Prim2RelOpF64 { public LT_F64() { super("<" ); } boolean op( double l, double r ) { return l< r; } }
  public static class LE_F64 extends Prim2RelOpF64 { public LE_F64() { super("<="); } boolean op( double l, double r ) { return l<=r; } }
  public static class GT_F64 extends Prim2RelOpF64 { public GT_F64() { super(">" ); } boolean op( double l, double r ) { return l> r; } }
  public static class GE_F64 extends Prim2RelOpF64 { public GE_F64() { super(">="); } boolean op( double l, double r ) { return l>=r; } }
  public static class EQ_F64 extends Prim2RelOpF64 { public EQ_F64() { super("=="); } boolean op( double l, double r ) { return l==r; } }
  public static class NE_F64 extends Prim2RelOpF64 { public NE_F64() { super("!="); } boolean op( double l, double r ) { return l!=r; } }


  // 2Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim2OpI64 extends PrimNode {
    Prim2OpI64( String name ) { super(name,TypeStruct.INT64_INT64,TypeInt.INT64); }
    @Override public Type apply( Type[] args ) { return TypeInt.con(op(args[DSP_IDX].getl(),args[ARG_IDX].getl())); }
    abstract long op( long x, long y );
  }

  public static class AddI64 extends Prim2OpI64 {
    public AddI64() { super("+"); }
    @Override long op( long l, long r ) { return l+r; }
  }

  public static class SubI64 extends Prim2OpI64 {
    public SubI64() { super("-"); }
    @Override long op( long l, long r ) { return l-r; }
  }

  public static class MulI64 extends Prim2OpI64 {
    public MulI64() { super("*"); }
    @Override long op( long l, long r ) { return l*r; }
  }

  public static class DivI64 extends Prim2OpI64 {
    public DivI64() { super("/"); }
    @Override long op( long l, long r ) { return l/r; } // Long division
  }

  public static class ModI64 extends Prim2OpI64 {
    public ModI64() { super("%"); }
    @Override long op( long l, long r ) { return l%r; }
  }

  public static class AndI64 extends Prim2OpI64 {
    public AndI64() { super("&"); }
    // And can preserve bit-width
    @Override public Type value() {
      if( is_keep() ) return Type.ALL;
      Type t1 = val(DSP_IDX), t2 = val(ARG_IDX);
      // 0 AND anything is 0
      if( t1 == Type. NIL || t2 == Type. NIL ) return Type. NIL;
      if( t1 == Type.XNIL || t2 == Type.XNIL ) return Type.XNIL;
      // If either is high - results might fall to something reasonable
      if( t1.above_center() || t2.above_center() )
        return TypeInt.INT64.dual();
      // Both are low-or-constant, and one is not valid - return bottom result
      if( !t1.isa(TypeInt.INT64) || !t2.isa(TypeInt.INT64) )
        return TypeInt.INT64;
      // If both are constant ints, return the constant math.
      if( t1.is_con() && t2.is_con() )
        return TypeInt.con(t1.getl() & t2.getl());
      if( !(t1 instanceof TypeInt) || !(t2 instanceof TypeInt) )
        return TypeInt.INT64;
      // Preserve width
      return ((TypeInt)t1).minsize((TypeInt)t2);
    }
    @Override long op( long l, long r ) { return l&r; }
  }

  public static class OrI64 extends Prim2OpI64 {
    public OrI64() { super("|"); }
    // And can preserve bit-width
    @Override public Type value() {
      if( is_keep() ) return Type.ALL;
      Type t1 = val(DSP_IDX), t2 = val(ARG_IDX);
      // 0 OR anything is that thing
      if( t1 == Type.NIL || t1 == Type.XNIL ) return t2;
      if( t2 == Type.NIL || t2 == Type.XNIL ) return t1;
      // If either is high - results might fall to something reasonable
      if( t1.above_center() || t2.above_center() )
        return TypeInt.INT64.dual();
      // Both are low-or-constant, and one is not valid - return bottom result
      if( !t1.isa(TypeInt.INT64) || !t2.isa(TypeInt.INT64) )
        return TypeInt.INT64;
      // If both are constant ints, return the constant math.
      if( t1.is_con() && t2.is_con() )
        return TypeInt.con(t1.getl() | t2.getl());
      if( !(t1 instanceof TypeInt) || !(t2 instanceof TypeInt) )
        return TypeInt.INT64;
      // Preserve width
      return ((TypeInt)t1).maxsize((TypeInt)t2);
    }
    @Override long op( long l, long r ) { return l&r; }
  }

  // 2RelOps have uniform input types, and bool output
  abstract static class Prim2RelOpI64 extends PrimNode {
    Prim2RelOpI64( String name ) { super(name,TypeStruct.INT64_INT64,TypeInt.BOOL); }
    @Override public Type apply( Type[] args ) { return op(args[DSP_IDX].getl(),args[ARG_IDX].getl())?TypeInt.TRUE:TypeInt.FALSE; }
    abstract boolean op( long x, long y );
  }

  public static class LT_I64 extends Prim2RelOpI64 { public LT_I64() { super("<" ); } boolean op( long l, long r ) { return l< r; } }
  public static class LE_I64 extends Prim2RelOpI64 { public LE_I64() { super("<="); } boolean op( long l, long r ) { return l<=r; } }
  public static class GT_I64 extends Prim2RelOpI64 { public GT_I64() { super(">" ); } boolean op( long l, long r ) { return l> r; } }
  public static class GE_I64 extends Prim2RelOpI64 { public GE_I64() { super(">="); } boolean op( long l, long r ) { return l>=r; } }
  public static class EQ_I64 extends Prim2RelOpI64 { public EQ_I64() { super("=="); } boolean op( long l, long r ) { return l==r; } }
  public static class NE_I64 extends Prim2RelOpI64 { public NE_I64() { super("!="); } boolean op( long l, long r ) { return l!=r; } }


  public static class EQ_OOP extends PrimNode {
    public EQ_OOP() { super("==",TypeMemPtr.OOP_OOP,TypeInt.BOOL); }
    @Override public Type value() {
      if( is_keep() ) return Type.ALL;
      // Oop-equivalence is based on pointer-equivalence NOT on a "deep equals".
      // Probably need a java-like "eq" vs "==" to mean deep-equals.  You are
      // equals if your inputs are the same node, and you are unequals if your
      // input is 2 different NewNodes (or casts of NewNodes).  Otherwise, you
      // have to do the runtime test.
      Node in1 = in(DSP_IDX), in2 = in(ARG_IDX);
      if( in1==in2 ) return TypeInt.TRUE;
      Node nn1 = in1.in(0), nn2 = in2.in(0);
      if( nn1 instanceof NewNode &&
          nn2 instanceof NewNode &&
          nn1 != nn2 ) return TypeInt.FALSE;
      // Constants can only do nil-vs-not-nil, since e.g. two strings "abc" and
      // "abc" are equal constants in the type system but can be two different
      // string pointers.
      Type t1 = in1._val;
      Type t2 = in2._val;
      if( t1==Type.NIL || t1==Type.XNIL ) return vs_nil(t2,TypeInt.TRUE,TypeInt.FALSE);
      if( t2==Type.NIL || t2==Type.XNIL ) return vs_nil(t1,TypeInt.TRUE,TypeInt.FALSE);
      if( t1.above_center() || t2.above_center() ) return TypeInt.BOOL.dual();
      return TypeInt.BOOL;
    }
    @Override public TypeInt apply( Type[] args ) { throw AA.unimpl(); }
    static Type vs_nil( Type tx, Type t, Type f ) {
      if( tx==Type.NIL || tx==Type.XNIL ) return t;
      if( tx.above_center() ) return tx.isa(Type.NIL) ? TypeInt.BOOL.dual() : f;
      return tx.must_nil() ? TypeInt.BOOL : f;
    }
  }

  public static class NE_OOP extends PrimNode {
    public NE_OOP() { super("!=",TypeMemPtr.OOP_OOP,TypeInt.BOOL); }
    @Override public Type value() {
      if( is_keep() ) return Type.ALL;
      // Oop-equivalence is based on pointer-equivalence NOT on a "deep equals".
      // Probably need a java-like "===" vs "==" to mean deep-equals.  You are
      // equals if your inputs are the same node, and you are unequals if your
      // input is 2 different NewNodes (or casts of NewNodes).  Otherwise, you
      // have to do the runtime test.
      Node in1 = in(DSP_IDX), in2 = in(ARG_IDX);
      if( in1==in2 ) return TypeInt.FALSE;
      Node nn1 = in1.in(0), nn2 = in2.in(0);
      if( nn1 instanceof NewNode &&
          nn2 instanceof NewNode &&
          nn1 != nn2 ) return TypeInt.TRUE;
      // Constants can only do nil-vs-not-nil, since e.g. two strings "abc" and
      // "abc" are equal constants in the type system but can be two different
      // string pointers.
      Type t1 = in1._val;
      Type t2 = in2._val;
      if( t1==Type.NIL || t1==Type.XNIL ) return EQ_OOP.vs_nil(t2,TypeInt.FALSE,TypeInt.TRUE);
      if( t2==Type.NIL || t2==Type.XNIL ) return EQ_OOP.vs_nil(t1,TypeInt.FALSE,TypeInt.TRUE);
      if( t1.above_center() || t2.above_center() ) return TypeInt.BOOL.dual();
      return TypeInt.BOOL;
    }
    @Override public TypeInt apply( Type[] args ) { throw AA.unimpl(); }
  }


  public static class Not extends PrimNode {
    // Rare function which takes a Scalar (works for both ints and ptrs)
    public Not() { super("!",TypeStruct.SCALAR1,TypeInt.BOOL); }
    @Override public Type value() {
      if( is_keep() ) return Type.ALL;
      Type t = val(DSP_IDX);
      if( t== Type.XNIL ||
          t== Type. NIL ||
          t== TypeInt.ZERO )
        return TypeInt.TRUE;
      if( t. may_nil() ) return TypeInt.BOOL.dual();
      if( t.must_nil() ) return TypeInt.BOOL;
      return Type.NIL;          // Cannot be a nil, so return a nil
    }
    @Override public TypeInt apply( Type[] args ) { throw AA.unimpl(); }
  }

  public static class RandI64 extends PrimNode {
    public RandI64() { super("rand",TypeStruct.INT64,TypeInt.INT64); }
    @Override public Type value() {
      if( is_keep() ) return Type.ALL;
      Type t = val(DSP_IDX);
      if( t.above_center() ) return TypeInt.BOOL.dual();
      if( TypeInt.INT64.dual().isa(t) && t.isa(TypeInt.INT64) )
        return t.meet(TypeInt.FALSE);
      return t.oob(TypeInt.INT64);
    }
    @Override public TypeInt apply( Type[] args ) { throw AA.unimpl(); }
    // Rands have hidden internal state; 2 Rands are never equal
    @Override public boolean equals(Object o) { return this==o; }
  }

  //// Classic '&&' short-circuit.  The RHS is a *Thunk* not a value.  Inlines
  //// immediate into the operators' wrapper function, which in turn aggressively
  //// inlines during parsing.
  //public static class AndThen extends PrimNode {
  //  private static final TypeStruct ANDTHEN = TypeStruct.make2flds("pred",Type.SCALAR,"thunk",Type.SCALAR);
  //  // Takes a value on the LHS, and a THUNK on the RHS.
  //  public AndThen() { super("&&",ANDTHEN,Type.SCALAR); _thunk_rhs=true; }
  //  // Expect this to inline everytime
  //  @Override public Node ideal_grow() {
  //    if( _defs._len != ARG_IDX+1 ) return null; // Already did this
  //    try(GVNGCM.Build<Node> X = Env.GVN.new Build<>()) {
  //      Node ctl = in(CTL_IDX);
  //      Node mem = in(MEM_IDX);
  //      Node lhs = in(DSP_IDX);
  //      Node rhs = in(ARG_IDX);
  //      // Expand to if/then/else
  //      Node iff = X.xform(new IfNode(ctl,lhs));
  //      Node fal = X.xform(new CProjNode(iff,0));
  //      Node tru = X.xform(new CProjNode(iff,1));
  //      // Call on true branch; if false do not call.
  //      Node cal = X.xform(new CallNode(true,_badargs,tru,mem,rhs));
  //      //Node cep = X.xform(new CallEpiNode(cal,Env.DEFMEM));
  //      //Node ccc = X.xform(new CProjNode(cep));
  //      //Node memc= X.xform(new MProjNode(cep));
  //      //Node rez = X.xform(new  ProjNode(cep,AA.REZ_IDX));
  //      //// Region merging results
  //      //Node reg = X.xform(new RegionNode(null,fal,ccc));
  //      //Node phi = X.xform(new PhiNode(Type.SCALAR,null,reg,Node.con(Type.XNIL),rez ));
  //      //Node phim= X.xform(new PhiNode(TypeMem.MEM,null,reg,mem,memc ));
  //      //// Plug into self & trigger is_copy
  //      //set_def(0,reg );
  //      //set_def(1,phim);
  //      //set_def(2,phi );
  //      //pop();   pop();     // Remove args, trigger is_copy
  //      //X.add(this);
  //      //for( Node use : _uses ) X.add(use);
  //      //return null;
  //      throw unimpl();
  //    }
  //  }
  //  @Override public Type value() {
  //    return TypeTuple.RET;
  //  }
  //  @Override public TypeMem live_use(Node def ) {
  //    if( def==in(0) ) return TypeMem.ALIVE; // Control
  //    if( def==in(1) ) return TypeMem.ALLMEM; // Force maximal liveness, since will inline
  //    return TypeMem.ALIVE; // Force maximal liveness, since will inline
  //  }
  //  //@Override public TV2 new_tvar(String alloc_site) { return TV2.make("Thunk",this,alloc_site); }
  //  @Override public TypeInt apply( Type[] args ) { throw AA.unimpl(); }
  //  @Override public Node is_copy(int idx) {
  //    return _defs._len==ARG_IDX+2 ? null : in(idx);
  //  }
  //}
  //
  //// Classic '||' short-circuit.  The RHS is a *Thunk* not a value.  Inlines
  //// immediate into the operators' wrapper function, which in turn aggressively
  //// inlines during parsing.
  //public static class OrElse extends PrimNode {
  //  private static final TypeStruct ORELSE = TypeStruct.make2flds("pred",Type.SCALAR,"thunk",Type.SCALAR);
  //  // Takes a value on the LHS, and a THUNK on the RHS.
  //  public OrElse() { super("||",ORELSE,Type.SCALAR); _thunk_rhs=true; }
  //  // Expect this to inline everytime
  //  @Override public Node ideal_grow() {
  //    if( _defs._len != ARG_IDX+1 ) return null; // Already did this
  //    try(GVNGCM.Build<Node> X = Env.GVN.new Build<>()) {
  //      Node ctl = in(CTL_IDX);
  //      Node mem = in(MEM_IDX);
  //      Node lhs = in(DSP_IDX);
  //      Node rhs = in(ARG_IDX);
  //      // Expand to if/then/else
  //      Node iff = X.xform(new IfNode(ctl,lhs));
  //      Node fal = X.xform(new CProjNode(iff,0));
  //      Node tru = X.xform(new CProjNode(iff,1));
  //      // Call on false branch; if true do not call.
  //      Node cal = X.xform(new CallNode(true,_badargs,fal,mem,rhs));
  //      //Node cep = X.xform(new CallEpiNode(cal,Env.DEFMEM));
  //      //Node ccc = X.xform(new CProjNode(cep));
  //      //Node memc= X.xform(new MProjNode(cep));
  //      //Node rez = X.xform(new  ProjNode(cep,AA.REZ_IDX));
  //      //// Region merging results
  //      //Node reg = X.xform(new RegionNode(null,tru,ccc));
  //      //Node phi = X.xform(new PhiNode(Type.SCALAR,null,reg,lhs,rez ));
  //      //Node phim= X.xform(new PhiNode(TypeMem.MEM,null,reg,mem,memc ));
  //      //// Plug into self & trigger is_copy
  //      //set_def(0,reg );
  //      //set_def(1,phim);
  //      //set_def(2,phi );
  //      //pop();   pop();     // Remove args, trigger is_copy
  //      //X.add(this);
  //      //for( Node use : _uses ) X.add(use);
  //      //return null;
  //      throw unimpl();
  //    }
  //  }
  //  @Override public Type value() {
  //    return TypeTuple.RET;
  //  }
  //  @Override public TypeMem live_use(Node def ) {
  //    if( def==in(0) ) return TypeMem.ALIVE; // Control
  //    if( def==in(1) ) return TypeMem.ALLMEM; // Force maximal liveness, since will inline
  //    return TypeMem.ALIVE; // Force maximal liveness, since will inline
  //  }
  //  //@Override public TV2 new_tvar(String alloc_site) { return TV2.make("Thunk",this,alloc_site); }
  //  @Override public TypeInt apply( Type[] args ) { throw AA.unimpl(); }
  //  @Override public Node is_copy(int idx) {
  //    return _defs._len==ARG_IDX+2 ? null : in(idx);
  //  }
  //}

}
