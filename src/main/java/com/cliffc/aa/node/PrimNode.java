package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.tvar.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.Util;

import static com.cliffc.aa.AA.*;
import static com.cliffc.aa.type.TypeFld.Access;


// Primitives are nodes to do primitive operations.  Internally they carry a
// '_formals' to type their arguments.  Similar to functions and FunNodes and
// unlike structs and NewNodes, the arguments are ordered.  The inputs to
// the Node itself and the formals are numbered.  Example:
// - index CTL_IDX, typically null
// - index MEM_IDX, typically null except for e.g. Load/Store primitives
// - index DSP_IDX, typically the left arg, NOT the Display nor 'self'
// - index ARG_IDX, typically the right arg

// Primitives use their apply() call as the transfer function and expect the
// args in this order.

// Primitives are wrapped as functions when returned from Env lookup, although
// the immediate lookup+apply is optimized to just make a new primitive.  See
// FunNode for function Node structure.  The wrapping function preserves the
// order in the ParmNodes.

public abstract class PrimNode extends Node {
  public final String _name;    // Unique name (and program bits)
  public final TypeFunPtr _tfp; // FIDX, nargs, display argument, WRAPPED primitive return type
  public final TypeTuple _formals; // Formals are indexed by order NOT name and are unwrapped prims.
  public final TypeNil _ret;    // unrapped primitive return
  public final boolean _is_lazy;// 2nd arg is lazy (thunked by parser)
  Parse[] _badargs;             // Filled in when inlined in CallNode
  public PrimNode( String name, TypeTuple formals, TypeNil ret ) { this(name,false,formals,ret); }
  public PrimNode( String name, boolean is_lazy, TypeTuple formals, TypeNil ret ) {
    super(OP_PRIM);
    _name = name;
    _is_lazy = is_lazy;
    int fidx = BitsFun.new_fidx();
    for( int i=DSP_IDX; i<formals._ts.length; i++ ) assert formals._ts[i] instanceof TypeNil; // Formals are unwrapped
    _formals = formals;
    _ret = ret;
    _tfp=TypeFunPtr.make(fidx,formals.len(),TypeMemPtr.NO_DISP,ret);
    _badargs=null;
  }

  // Int/Float primitives.  
  public static final StructNode  INT,  FLT; // Prototype; has all the primitive operators
  public static final    NewNode NINT, NFLT; // Allocation of prototype object
  public static final  MProjNode MINT, MFLT; // Prototype memory 
  public static final   ProjNode PINT, PFLT; // Pointer to prototype
  public static final StructNode XINT, XFLT; // Worse-case instances

  static {
    // Requires Env be initialized first.  Uses standard Env.TOP scope memory
    Node mem = Env.SCP_0.mem();
    
     FLT = new StructNode(false,false,null, "flt:", Type.ALL).init();
    NFLT = new    NewNode(mem,FLT).init();
    MFLT = new  MProjNode(NFLT).init();
    PFLT = new   ProjNode(NFLT,REZ_IDX).init();
    XFLT = NFLT.make_con(TypeStruct.make_flt(PFLT._val,TypeFlt.FLT64));
    
     INT = new StructNode(false,false,null,"int:", Type.ALL).init();
    NINT = new    NewNode(MFLT,INT).init();
    MINT = new  MProjNode(NINT).init();
    PINT = new   ProjNode(NINT,REZ_IDX).init();
    XINT = NINT.make_con(TypeStruct.make_int(PINT._val,TypeInt.INT64));

    Env.SCP_0.set_mem(MINT);
  }
  
  private static PrimNode[] PRIMS = null; // All primitives

  public static PrimNode[] PRIMS() {
    if( PRIMS!=null ) return PRIMS;

    // int opers
    PrimNode[][] INTS = new PrimNode[][]{
      { new AddI64(), new AddIF64() },
      { new SubI64(), new SubIF64() },
      { new MulI64(), new MulIF64() },
      { new DivI64(), new DivIF64() },
      { new LT_I64(), new LT_IF64() },
      { new LE_I64(), new LE_IF64() },
      { new GT_I64(), new GT_IF64() },
      { new GE_I64(), new GE_IF64() },
      { new EQ_I64(), new EQ_IF64() },
      { new NE_I64(), new NE_IF64() },
      { new MinusI64() }, { new NotI64() }, { new ModI64() },
      { new AndI64() }, { new OrI64() },
      { new AndThen() }, { new OrElse() },
    };

    PrimNode[][] FLTS = new PrimNode[][]{
      { new AddF64(), new AddFI64() },
      { new SubF64(), new SubFI64() },
      { new MulF64(), new MulFI64() },
      { new DivF64(), new DivFI64() },
      { new LT_F64(), new LT_FI64() },
      { new GE_F64(), new GE_FI64() },
      { new LE_F64(), new LE_FI64() },
      { new GT_F64(), new GT_FI64() },
      { new EQ_F64(), new EQ_FI64() },
      { new NE_F64(), new NE_FI64() },
      { new MinusF64() },
      { new SinF64() },
    };
    // Other primitives, not binary operators
    PrimNode rand = new RandI64();
    PrimNode[] others = new PrimNode[] {
      // These are called like a function, so do not have a precedence
      //rand,
      //new ConvertI64F64(),

      //new EQ_OOP(), new NE_OOP(), 
      //// These are balanced-ops, called by Parse.term()
      //new MemPrimNode.ReadPrimNode.LValueRead  (), // Read  an L-Value: (ary,idx) ==> elem
      //new MemPrimNode.ReadPrimNode.LValueWrite (), // Write an L-Value: (ary,idx,elem) ==> elem
      //new MemPrimNode.ReadPrimNode.LValueWriteFinal(), // Final Write an L-Value: (ary,idx,elem) ==> elem

      // These are unary ops, precedence determined outside 'Parse.expr'
      //new MemPrimNode.ReadPrimNode.LValueLength(), // The other array ops are "balanced ops" and use term() for precedence
    };

    // Gather
    Ary<PrimNode> allprims = new Ary<>(others);
    for( PrimNode prim : others ) allprims.push(prim);
    for( PrimNode[] prims : FLTS   ) for( PrimNode prim : prims ) allprims.push(prim);
    for( PrimNode[] prims : INTS   ) for( PrimNode prim : prims ) allprims.push(prim);
    PRIMS = allprims.asAry();

    // Build the int and float types and prototypes
    Env.STK_0.add_fld("flt",Access.Final,make_prim(PFLT,FLT,FLTS),null);
    Env.STK_0.add_fld("int",Access.Final,make_prim(PINT,INT,INTS),null);
    
    Env.KEEP_ALIVE.add_def(XINT);
    Env.KEEP_ALIVE.add_def(XFLT);
    Env.GVN.iter();
    
    // Math package
    Env.STK_0.add_fld("math",Access.Final,make_math(rand),null);

    return PRIMS;
  }

  // Make a wrapped primitive
  public static TypeStruct make_int(TypeInt ti) { return PINT._val==Type.ALL ? TypeStruct.ISUSED : TypeStruct.make_int(PINT._val,ti); }
  public static TypeStruct make_flt(TypeFlt td) { return PFLT._val==Type.ALL ? TypeStruct.ISUSED : TypeStruct.make_flt(PFLT._val,td); }
  public static Type make_wrap(TypeNil rez) {
    if( rez==TypeNil.XNIL ) return rez;
    return rez instanceof TypeInt ti ? make_int(ti) : make_flt((TypeFlt)rez);
  }

  // Primitive wrapped as a simple function.
  // Fun Parm_dsp [Parm_y] prim Ret
  // No memory, no RPC.  Display is first arg.
  private FunPtrNode as_fun( ) {
    if( is_oper() ) Oper.make(_name,_is_lazy);

    FunNode fun = new FunNode(this,_name);
    fun.add_def(new CRProjNode(fun._fidx).init()); // Need FIDX to make the default control
    fun.init();                 // Now register with parser
    if( _is_lazy ) add_def(fun); // Ctrl in slot 0
    ParmNode rpc = new ParmNode(0,fun,null,TypeRPC.ALL_CALL,Env.ALL_CALL).init();
    for( int i=_is_lazy ? MEM_IDX : DSP_IDX; i<_formals.len(); i++ ) {
      // Make a Parm for every formal
      Type tformal = _formals.at(i);
      Node nformal;
      if(      tformal==TypeInt.INT64   ) nformal = XINT;
      else if( tformal==TypeFlt.FLT64   ) nformal = XFLT;
      else if( tformal==Type.ALL        ) nformal = Env.ALL;
      else if( tformal==TypeFunPtr.THUNK) nformal = Env.THUNK;
      else if( tformal==TypeMem.ALLMEM  ) nformal = Env.ALLMEM;
      else throw unimpl();
      add_def(new ParmNode(i,fun,null,TypeNil.SCALAR,nformal).init());
    }
    add_def(PINT);
    add_def(PFLT);
    // The primitive, working on and producing wrapped prims
    init();
    // If lazy, need control and memory results
    Node zctrl = _is_lazy ? new CProjNode(this).init() : fun;
    Node zmem  = _is_lazy ? new MProjNode(this).init() : null;
    Node zrez  = _is_lazy ? new  ProjNode(this,REZ_IDX).init() : this;
    // Return the result
    RetNode ret = new RetNode(zctrl,zmem,zrez,rpc,fun).init();
    // FunPtr is UNBOUND here, will be bound when loaded thru a named struct to the Clazz.
    return new FunPtrNode(_name,ret,Env.ANY).init();
  }
  // Make and install a primitive Clazz.
  private static ProjNode make_prim( ProjNode ptr, StructNode rec, PrimNode[][] primss ) {
    for( PrimNode[] prims : primss ) {
      StructNode over = new StructNode(false,false,null,"",Type.ALL);
      int cnt=0;
      for( PrimNode prim : prims ) {
        String fld = (""+cnt++).intern();
        over.add_fld(fld,Access.Final,prim.as_fun(),null);
      }
      over.init();
      over.close();
      rec.add_fld(prims[0]._name,Access.Final,over,null);
    }
    Env.GVN.add_work_new(rec);
    rec._val = rec.value();
    rec.close();
    Env.SCP_0.add_type(rec._clz,rec); // type  String -> clazz Struct mapping, for types
    NewNode nnn = (NewNode)ptr.in(0);
    Env.PROTOS.put(nnn._alias,nnn); // alias mapping for instance lookup
    return ptr;
  }

  // Build and install match package
  private static ProjNode make_math(PrimNode rand) {
    StructNode rec = new StructNode(false,false,null,"",Type.ALL);
    var pi = NFLT.make_con(TypeStruct.make_flt(PFLT._val,TypeFlt.PI));
    rec.add_fld("pi",Access.Final,pi,null);
    rec.add_fld(rand._name,Access.Final,rand.as_fun(),null);
    rec.init();
    rec.close();
    Node mem = Env.SCP_0.mem();
    NewNode nnn = new NewNode(mem,rec).init();
    MProjNode mprj = new MProjNode(nnn).init();
    ProjNode ptr = new ProjNode(nnn,REZ_IDX).init();
    Env.SCP_0.set_mem(mprj);
    return ptr;
  }
  
  // Apply uses the same alignment as the arguments, ParmNodes, _formals.
  abstract TypeNil apply( TypeNil[] args ); // Execute primitive
  // Pretty print short primitive signature based on first argument:
  //  + :{int int -> int }  ==>>   + :int
  //  + :{flt flt -> flt }  ==>>   + :flt
  //  + :{str str -> str }  ==>>   + :str
  // str:{int     -> str }  ==>>  str:int
  // str:{flt     -> str }  ==>>  str:flt
  // == :{ptr ptr -> int1}  ==>>  == :ptr
  @Override public String xstr() { return _name; }
  private static final TypeNil[] TS = new TypeNil[ARG_IDX+1];
  @Override public Type value() {
    if( is_keep() ) return _val;
    // If all inputs are constants we constant-fold.  If any input is high, we
    // return high otherwise we return low.
    boolean is_con = true, has_high = false;
    for( int i=DSP_IDX; i<_formals.len(); i++ ) {
      TypeNil tformal = (TypeNil)_formals.at(i);
      Type twrap = val(i-DSP_IDX);
      Type tactual = twrap instanceof TypeStruct ts && ts.get(".")!=null ? ts.at(".") : tformal;
      TypeNil t = TS[i-DSP_IDX] = (TypeNil)tformal.dual().meet(tactual);
      // Expect: tformal is a expanded TypeStruct, so t is also.
      if( tactual != TypeNil.XNIL && !t.is_con() ) {
        is_con = false;         // Some non-constant
        if( t.above_center() ) has_high=true;
      }
    }
    return make_wrap(is_con ? apply(TS) : (has_high ? (TypeNil)_ret.dual() : _ret));
  }
  boolean is_oper() { return true; }


  @Override public boolean has_tvar() { return true; }

  // In the test HM, all primitives are a Lambda without a body.  Here they are
  // effectively Applies/Calls and the primitive computes a result.
  @Override TV3 _set_tvar() {
    // All arguments are pre-unified to unique copy bases, wrapped in a
    // primitive with a clazz reference
    for( int i=DSP_IDX; i<_formals.len(); i++ )
      in(i-DSP_IDX).set_tvar().unify(make_tvar(true,(TypeNil)_formals.at(i)),false);
    // Return is some primitive, and is typically never a copy
    return make_tvar(false,_ret);
  }
  
  // Make a wrapped TV3
  private static final String[] PRIM_FLDS = new String[]{"!","."};
  static TV3 make_tvar(boolean is_copy, TypeNil rez) {
    if( rez==TypeNil.XNIL ) throw unimpl();
    TV3 base = TVBase.make(is_copy,rez);
    if( rez instanceof TypeInt )
      return new TVStruct(PRIM_FLDS,new TV3[]{PINT.set_tvar(),base});
    if( rez instanceof TypeFlt )
      return new TVStruct(PRIM_FLDS,new TV3[]{PFLT.set_tvar(),base});
    throw unimpl();
  }

  // All work done in set_tvar, no need to unify
  @Override public boolean unify( boolean test ) { return false; }

  @Override public ErrMsg err( boolean fast ) {
    for( int i=DSP_IDX; i<_formals.len(); i++ ) {
      Type tactual = val(i-DSP_IDX);
      TypeNil tformal = (TypeNil)_formals.at(i);
      if( !tactual.isa(make_wrap(tformal)) )
        return _badargs==null ? ErrMsg.BADARGS : ErrMsg.typerr(_badargs[i],tactual, tformal);
    }
    return null;
  }
  // Prims are equal for same-name-same-signature (and same inputs).
  // E.g. float-minus of x and y is NOT the same as int-minus of x and y
  // despite both names being '-'.
  @Override public int hashCode() { return super.hashCode()+_name.hashCode()+(int)_formals._hash; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof PrimNode p) ) return false;
    return Util.eq(_name,p._name) && _formals==p._formals;
  }

  //public static class ConvertI64F64 extends PrimNode {
  //  public ConvertI64F64() { super("flt",TypeTuple.INT64,TypeFlt.FLT64); }
  //  @Override Type apply( Type[] args ) {
  //    return make_flt((double)unwrap_ii(args[0]));
  //  }
  //}

  // 1Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim1OpF64 extends PrimNode {
    Prim1OpF64( String name ) { super(name,TypeTuple.FLT64,TypeFlt.FLT64); }
    @Override TypeFlt apply( TypeNil[] args ) {  return TypeFlt.con(op(args[0].getd()));  }
    abstract double op( double d );
  }

  static class MinusF64 extends Prim1OpF64 { MinusF64() { super("-_"); } double op( double d ) { return -d; } }
  
  static class SinF64 extends Prim1OpF64 {
    SinF64() { super("sin"); }
    @Override boolean is_oper() { return false; }
    @Override double op( double d ) { throw AA.unimpl(); } 
  }

  // 1Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim1OpI64 extends PrimNode {
    Prim1OpI64( String name ) { super(name,TypeTuple.INT64,TypeInt.INT64); }
    @Override TypeInt apply( TypeNil[] args ) {  return TypeInt.con(op(args[0].getl()));  }
    abstract long op( long d );
  }

  static class MinusI64 extends Prim1OpI64 { MinusI64() { super("-_"); } long op( long x ) { return -x; } }
  static class NotI64 extends PrimNode {
    public NotI64() { super("!_",TypeTuple.INT64,TypeInt.BOOL); }
    @Override public TypeNil apply( TypeNil[] args ) {
      TypeNil t0 = args[0];
      if( t0._nil )
        return t0._sub
          ? TypeInt.BOOL.dual() // Choice nil and choice nint, could go either way
          : TypeInt.TRUE;       // Yes nil & ignore sub, so always true
      return t0._sub
        ? TypeNil.XNIL          // not-nil, so always false
        : TypeInt.BOOL;         // Could go either way
    }
  }


  // 2Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim2OpF64 extends PrimNode {
    Prim2OpF64( String name ) { super(name,TypeTuple.FLT64_FLT64,TypeFlt.FLT64); }
    @Override TypeFlt apply( TypeNil[] args ) { return TypeFlt.con(op(args[0].getd(),args[1].getd())); }
    abstract double op( double x, double y );
  }

  static class AddF64 extends Prim2OpF64 { AddF64() { super("_+_"); } double op( double l, double r ) { return l+r; } }
  static class SubF64 extends Prim2OpF64 { SubF64() { super("_-_"); } double op( double l, double r ) { return l-r; } }
  static class MulF64 extends Prim2OpF64 { MulF64() { super("_*_"); } double op( double l, double r ) { return l*r; } }
  static class DivF64 extends Prim2OpF64 { DivF64() { super("_/_"); } double op( double l, double r ) { return l/r; } }

  // 2RelOps have uniform input types, and bool output
  abstract static class Prim2RelOpF64 extends PrimNode {
    Prim2RelOpF64( String name ) { super(name,TypeTuple.FLT64_FLT64,TypeInt.BOOL); }
    @Override public TypeNil apply( TypeNil[] args ) { return op(args[0].getd(),args[1].getd()) ? TypeInt.TRUE : TypeNil.XNIL ; }
    abstract boolean op( double x, double y );
  }

  public static class LT_F64 extends Prim2RelOpF64 { public LT_F64() { super("_<_" ); } boolean op( double l, double r ) { return l< r; } }
  public static class LE_F64 extends Prim2RelOpF64 { public LE_F64() { super("_<=_"); } boolean op( double l, double r ) { return l<=r; } }
  public static class GT_F64 extends Prim2RelOpF64 { public GT_F64() { super("_>_" ); } boolean op( double l, double r ) { return l> r; } }
  public static class GE_F64 extends Prim2RelOpF64 { public GE_F64() { super("_>=_"); } boolean op( double l, double r ) { return l>=r; } }
  public static class EQ_F64 extends Prim2RelOpF64 { public EQ_F64() { super("_==_"); } boolean op( double l, double r ) { return l==r; } }
  public static class NE_F64 extends Prim2RelOpF64 { public NE_F64() { super("_!=_"); } boolean op( double l, double r ) { return l!=r; } }

  // 2RelOps have uniform input types, and bool output
  abstract static class Prim2RelOpFI64 extends PrimNode {
    Prim2RelOpFI64( String name ) { super(name,TypeTuple.FLT64_INT64,TypeInt.BOOL); }
    @Override public TypeNil apply( TypeNil[] args ) { return op(args[0].getd(),args[1].getl()) ? TypeInt.TRUE : TypeNil.XNIL ; }
    abstract boolean op( double x, long y );
  }

  public static class LT_FI64 extends Prim2RelOpFI64 { public LT_FI64() { super("_<_" ); } boolean op( double l, long r ) { return l< r; } }
  public static class LE_FI64 extends Prim2RelOpFI64 { public LE_FI64() { super("_<=_"); } boolean op( double l, long r ) { return l<=r; } }
  public static class GT_FI64 extends Prim2RelOpFI64 { public GT_FI64() { super("_>_" ); } boolean op( double l, long r ) { return l> r; } }
  public static class GE_FI64 extends Prim2RelOpFI64 { public GE_FI64() { super("_>=_"); } boolean op( double l, long r ) { return l>=r; } }
  public static class EQ_FI64 extends Prim2RelOpFI64 { public EQ_FI64() { super("_==_"); } boolean op( double l, long r ) { return l==r; } }
  public static class NE_FI64 extends Prim2RelOpFI64 { public NE_FI64() { super("_!=_"); } boolean op( double l, long r ) { return l!=r; } }


  // 2Ops have uniform input/output types, so take a shortcut on name printing
  abstract static class Prim2OpI64 extends PrimNode {
    Prim2OpI64( String name ) { super(name,TypeTuple.INT64_INT64,TypeInt.INT64); }
    @Override public TypeNil apply( TypeNil[] args ) { return TypeInt.con(op(args[0].getl(),args[1].getl())); }
    abstract long op( long x, long y );
  }

  static class AddI64 extends Prim2OpI64 { AddI64() { super("_+_"); } long op( long l, long r ) { return l+r; } }
  static class SubI64 extends Prim2OpI64 { SubI64() { super("_-_"); } long op( long l, long r ) { return l-r; } }
  static class MulI64 extends Prim2OpI64 { MulI64() { super("_*_"); } long op( long l, long r ) { return l*r; } }
  static class DivI64 extends Prim2OpI64 { DivI64() { super("_/_"); } long op( long l, long r ) { return r==0 ? 0 : l/r; } } // Long division
  static class ModI64 extends Prim2OpI64 { ModI64() { super("_%_"); } long op( long l, long r ) { return r==0 ? 0 : l%r; } }

  abstract static class Prim2OpIF64 extends PrimNode {
    Prim2OpIF64( String name ) { super(name,TypeTuple.INT64_FLT64,TypeFlt.FLT64); }
    @Override public TypeFlt apply( TypeNil[] args ) { return TypeFlt.con(op(args[0].getl(),args[1].getd())); }
    abstract double op( long x, double y );
  }
  static class AddIF64 extends Prim2OpIF64 { AddIF64() { super("_+_"); } double op( long l, double r ) { return l+r; } }
  static class SubIF64 extends Prim2OpIF64 { SubIF64() { super("_-_"); } double op( long l, double r ) { return l-r; } }
  static class MulIF64 extends Prim2OpIF64 { MulIF64() { super("_*_"); } double op( long l, double r ) { return l*r; } }
  static class DivIF64 extends Prim2OpIF64 { DivIF64() { super("_/_"); } double op( long l, double r ) { return l/r; } } // Float division, by 0 gives infinity

  abstract static class Prim2OpFI64 extends PrimNode {
    Prim2OpFI64( String name ) { super(name,TypeTuple.FLT64_INT64,TypeFlt.FLT64); }
    @Override public TypeFlt apply( TypeNil[] args ) { return TypeFlt.con(op(args[0].getd(),args[1].getl())); }
    abstract double op( double x, long y );
  }
  static class AddFI64 extends Prim2OpFI64 { AddFI64() { super("_+_"); } double op( double l, long r ) { return l+r; } }
  static class SubFI64 extends Prim2OpFI64 { SubFI64() { super("_-_"); } double op( double l, long r ) { return l-r; } }
  static class MulFI64 extends Prim2OpFI64 { MulFI64() { super("_*_"); } double op( double l, long r ) { return l*r; } }
  static class DivFI64 extends Prim2OpFI64 { DivFI64() { super("_/_"); } double op( double l, long r ) { return l/r; } } // Float division, by 0 gives infinity

  public static class AndI64 extends Prim2OpI64 {
    public AndI64() { super("_&_"); }
    // And can preserve bit-width
    @Override long op( long l, long r ) { return l&r; }
  }

  public static class OrI64 extends Prim2OpI64 {
    public OrI64() { super("_|_"); }
    // And can preserve bit-width
    @Override long op( long l, long r ) { return l&r; }
  }

  // 2RelOps have uniform input types, and bool output
  abstract static class Prim2RelOpI64 extends PrimNode {
    Prim2RelOpI64( String name ) { super(name,TypeTuple.INT64_INT64,TypeInt.BOOL); }
    @Override public TypeNil apply( TypeNil[] args ) { return op(args[0].getl(),args[1].getl()) ? TypeInt.TRUE : TypeNil.XNIL; }
    abstract boolean op( long x, long y );
  }

  public static class LT_I64 extends Prim2RelOpI64 { public LT_I64() { super("_<_" ); } boolean op( long l, long r ) { return l< r; } }
  public static class LE_I64 extends Prim2RelOpI64 { public LE_I64() { super("_<=_"); } boolean op( long l, long r ) { return l<=r; } }
  public static class GT_I64 extends Prim2RelOpI64 { public GT_I64() { super("_>_" ); } boolean op( long l, long r ) { return l> r; } }
  public static class GE_I64 extends Prim2RelOpI64 { public GE_I64() { super("_>=_"); } boolean op( long l, long r ) { return l>=r; } }
  public static class EQ_I64 extends Prim2RelOpI64 { public EQ_I64() { super("_==_"); } boolean op( long l, long r ) { return l==r; } }
  public static class NE_I64 extends Prim2RelOpI64 { public NE_I64() { super("_!=_"); } boolean op( long l, long r ) { return l!=r; } }

  abstract static class Prim2RelOpIF64 extends PrimNode {
    Prim2RelOpIF64( String name ) { super(name,TypeTuple.INT64_FLT64,TypeInt.BOOL); }
    @Override public TypeNil apply( TypeNil[] args ) { return op(args[0].getl(),args[1].getd()) ? TypeInt.TRUE : TypeNil.XNIL; }
    abstract boolean op( long x, double y );
  }

  public static class LT_IF64 extends Prim2RelOpIF64 { public LT_IF64() { super("_<_" ); } boolean op( long l, double r ) { return l< r; } }
  public static class LE_IF64 extends Prim2RelOpIF64 { public LE_IF64() { super("_<=_"); } boolean op( long l, double r ) { return l<=r; } }
  public static class GT_IF64 extends Prim2RelOpIF64 { public GT_IF64() { super("_>_" ); } boolean op( long l, double r ) { return l> r; } }
  public static class GE_IF64 extends Prim2RelOpIF64 { public GE_IF64() { super("_>=_"); } boolean op( long l, double r ) { return l>=r; } }
  public static class EQ_IF64 extends Prim2RelOpIF64 { public EQ_IF64() { super("_==_"); } boolean op( long l, double r ) { return l==r; } }
  public static class NE_IF64 extends Prim2RelOpIF64 { public NE_IF64() { super("_!=_"); } boolean op( long l, double r ) { return l!=r; } }


  public static class EQ_OOP extends PrimNode {
    public EQ_OOP() { super("_==_",TypeTuple.OOP_OOP,TypeInt.BOOL); }
    @Override public TypeStruct value() {
      //if( is_keep() ) return _val;
      //// Oop-equivalence is based on pointer-equivalence NOT on a "deep equals".
      //// Probably need a java-like "eq" vs "==" to mean deep-equals.  You are
      //// equals if your inputs are the same node, and you are unequals if your
      //// input is 2 different NewNodes (or casts of NewNodes).  Otherwise, you
      //// have to do the runtime test.
      //Node in1 = in(0), in2 = in(1);
      //if( in1==in2 ) return TypeInt.TRUE;
      //Node nn1 = in1.in(0), nn2 = in2.in(0);
      //if( nn1 instanceof NewNode &&
      //    nn2 instanceof NewNode &&
      //    nn1 != nn2 ) return TypeInt.FALSE;
      //// Constants can only do nil-vs-not-nil, since e.g. two strings "abc" and
      //// "abc" are equal constants in the type system but can be two different
      //// string pointers.
      //Type t1 = in1._val;
      //Type t2 = in2._val;
      //if( t1==Type.NIL || t1==Type.XNIL ) return vs_nil(t2,TypeInt.TRUE,TypeInt.FALSE);
      //if( t2==Type.NIL || t2==Type.XNIL ) return vs_nil(t1,TypeInt.TRUE,TypeInt.FALSE);
      //if( t1.above_center() || t2.above_center() ) return TypeInt.BOOL.dual();
      //return TypeInt.BOOL;
      throw unimpl();
    }
    @Override public TypeNil apply( TypeNil[] args ) { throw AA.unimpl(); }
    static Type vs_nil( Type tx, Type t, Type f ) {
      if( tx==TypeNil.XNIL ) return t;
      //if( tx.above_center() ) return tx.isa(TypeNil.XNIL) ? TypeInt.BOOL.dual() : f;
      //return tx.must_nil() ? TypeInt.BOOL : f;
      throw unimpl();
    }
  }

  public static class NE_OOP extends PrimNode {
    public NE_OOP() { super("_!=_",TypeTuple.OOP_OOP,TypeInt.BOOL); }
    @Override public TypeStruct value() {
      //if( is_keep() ) return _val;
      //// Oop-equivalence is based on pointer-equivalence NOT on a "deep equals".
      //// Probably need a java-like "===" vs "==" to mean deep-equals.  You are
      //// equals if your inputs are the same node, and you are unequals if your
      //// input is 2 different NewNodes (or casts of NewNodes).  Otherwise, you
      //// have to do the runtime test.
      //Node in1 = in(0), in2 = in(1);
      //if( in1==in2 ) return TypeInt.FALSE;
      //Node nn1 = in1.in(0), nn2 = in2.in(0);
      //if( nn1 instanceof NewNode &&
      //    nn2 instanceof NewNode &&
      //    nn1 != nn2 ) return TypeInt.TRUE;
      //// Constants can only do nil-vs-not-nil, since e.g. two strings "abc" and
      //// "abc" are equal constants in the type system but can be two different
      //// string pointers.
      //Type t1 = in1._val;
      //Type t2 = in2._val;
      //if( t1==Type.NIL || t1==Type.XNIL ) return EQ_OOP.vs_nil(t2,TypeInt.FALSE,TypeInt.TRUE);
      //if( t2==Type.NIL || t2==Type.XNIL ) return EQ_OOP.vs_nil(t1,TypeInt.FALSE,TypeInt.TRUE);
      //if( t1.above_center() || t2.above_center() ) return TypeInt.BOOL.dual();
      //return TypeInt.BOOL;
      throw unimpl();
    }
    @Override public TypeNil apply( TypeNil[] args ) { throw AA.unimpl(); }
  }


  public static class RandI64 extends PrimNode {
    public RandI64() { super("rand",TypeTuple.INT64,TypeInt.INT64); }
    @Override boolean is_oper() { return false; }
    @Override public TypeNil apply( TypeNil[] args ) { throw AA.unimpl(); }
    // Rands have hidden internal state; 2 Rands are never equal
    @Override public boolean equals(Object o) { return this==o; }
  }

  // Classic '&&' short-circuit.  Lazy in the RHS, so passed a 'thunk', a
  // zero-argument function to be evaluated if the LHS is true.
  // TODO: Move to a (not yet made) XNIL/SCAlAR class.
  public static class AndThen extends PrimNode {
    private static final TypeTuple ANDTHEN = TypeTuple.make(Type.CTRL, TypeMem.ALLMEM, TypeInt.INT64, TypeFunPtr.THUNK); // {val tfp -> val }
    // Takes a value on the LHS, and a THUNK on the RHS.
    public AndThen() { super("_&&_",true/*lazy*/,ANDTHEN,TypeNil.SCALAR); }
    @Override public TypeNil apply(TypeNil[] ts) { throw unimpl(); }
    @Override public Type value() { return TypeTuple.RET; }
    // Expect this to inline everytime
    @Override public Node ideal_grow() {
      // Do not expand the base primitive, only clones.  This allows the base
      // primitive to inline as a primitive in all contexts.
      if( is_prim() ) return null; // Do not expand the base primitive, only clones
      if( len() <= ARG_IDX ) return null; // Already expanded
      // Once inlined, replace with an if/then/else diamond.
      Node ctl = in(CTL_IDX);
      Node mem = in(MEM_IDX);
      Node lhs = in(DSP_IDX);
      Node rhs = in(ARG_IDX);
      // Expand to if/then/else
      try(GVNGCM.Build<Node> X = Env.GVN.new Build<>()) {
        Node iff = X.xform(new IfNode(ctl,lhs));
        Node fal = X.xform(new CProjNode(iff,0));
        Node tru = X.xform(new CProjNode(iff,1));
        // Call on true branch; if false do not call.
        Node cal = X.xform(new CallNode(true,_badargs,tru,mem,rhs));
        Node cep = X.xform(new CallEpiNode(cal));
        Node ccc = X.xform(new CProjNode(cep));
        Node memc= X.xform(new MProjNode(cep));
        Node rez = X.xform(new  ProjNode(cep,AA.REZ_IDX));
        // Region merging results
        Node reg = X.init(new RegionNode(null,fal,ccc));
        Node phi = X.init(new PhiNode(Type.ALL,null,reg,Env.XNIL,rez ));
        Node phim= X.init(new PhiNode(TypeMem.ALLMEM,null,reg,mem,memc ));
        // Plug into self & trigger is_copy
        set_def(CTL_IDX,reg );
        set_def(MEM_IDX,phim);
        set_def(REZ_IDX,phi );
        while( _defs._len > ARG_IDX ) pop(); // Remove args, trigger is_copy
        return this;
      }
    }

    // Pre-cook type: { A { -> A } -> A }
    // Bind display to A
    // Bind arg3 to { -> A } and return to A
    @Override TV3 _set_tvar() {
      TV3 a = in(DSP_IDX).set_tvar();
      // Unify arg3 to a no-args lambda { -> A }
      TVLambda lam = new TVLambda(DSP_IDX,null,a);
      in(ARG_IDX).set_tvar().unify(lam,false);
      return a;
    }
    // All work done in set_tvar, no need to unify
    @Override public boolean unify( boolean test ) { return false; }

    // Unify trailing result ProjNode with the AndThen directly.
    @Override public boolean unify_proj( ProjNode proj, boolean test ) {
      assert proj._idx==REZ_IDX;
      return tvar().unify(proj.tvar(),test);
    }
    @Override public Node is_copy(int idx) {
      return _defs._len>ARG_IDX ? null : in(idx);
    }
  }

  // Classic '||' short-circuit.  Lazy in the RHS, so passed a 'thunk', a
  // zero-argument function to be evaluated if the LHS is false.
  // TODO: Move to a (not yet made) XNIL/SCAlAR class.
  public static class OrElse extends PrimNode {
    private static final TypeTuple ORELSE = TypeTuple.make(Type.CTRL, TypeMem.ALLMEM, TypeInt.INT64, TypeFunPtr.THUNK); // {val tfp -> val }
    // Takes a value on the LHS, and a THUNK on the RHS.
    public OrElse() { super("_||_",true/*lazy*/,ORELSE,TypeNil.SCALAR); }
    @Override public TypeNil apply(TypeNil[] ts) { throw unimpl(); }
    @Override public Type value() { return TypeTuple.RET; }
    // Expect this to inline everytime
    @Override public Node ideal_grow() {
      // Do not expand the base primitive, only clones.  This allows the base
      // primitive to inline as a primitive in all contexts.
      if( is_prim() ) return null; // Do not expand the base primitive, only clones
      if( len() <= ARG_IDX ) return null; // Already expanded
      Node ctl = in(CTL_IDX);
      Node mem = in(MEM_IDX);
      Node lhs = in(DSP_IDX);
      Node rhs = in(ARG_IDX);
      // Expand to if/then/else
      try(GVNGCM.Build<Node> X = Env.GVN.new Build<>()) {
        // Expand to if/then/else
        Node iff = X.xform(new IfNode(ctl,lhs));
        Node fal = X.xform(new CProjNode(iff,0));
        Node tru = X.xform(new CProjNode(iff,1));
        // Call on false branch; if true do not call.
        Node cal = X.xform(new CallNode(true,_badargs,fal,mem,rhs));
        Node cep = X.xform(new CallEpiNode(cal));
        Node ccc = X.xform(new CProjNode(cep));
        Node memc= X.xform(new MProjNode(cep));
        Node rez = X.xform(new  ProjNode(cep,AA.REZ_IDX));
        // Region merging results
        Node reg = X.init(new RegionNode(null,tru,ccc));
        Node phi = X.init(new PhiNode(Type.ALL,null,reg,lhs,rez ));
        Node phim= X.init(new PhiNode(TypeMem.ALLMEM,null,reg,mem,memc ));
        // Plug into self & trigger is_copy
        set_def(CTL_IDX,reg );
        set_def(MEM_IDX,phim);
        set_def(REZ_IDX,phi );
        while( _defs._len > ARG_IDX ) pop(); // Remove args, trigger is_copy
        return this;
      }
    }

    // Pre-cook type: { A { -> A } -> A }
    // Bind display to A
    // Bind arg3 to { -> A } and return to A
    @Override TV3 _set_tvar() {
      TV3 a = in(DSP_IDX).set_tvar();
      // Unify arg3 to a no-args lambda { -> A }
      TVLambda lam = new TVLambda(DSP_IDX,null,a);
      in(ARG_IDX).set_tvar().unify(lam,false);
      return a;
    }
    // All work done in set_tvar, no need to unify
    @Override public boolean unify( boolean test ) { return false; }

    // Unify trailing result ProjNode with the AndThen directly.
    @Override public boolean unify_proj( ProjNode proj, boolean test ) {
      assert proj._idx==REZ_IDX;
      return tvar().unify(proj.tvar(),test);
    }
    @Override public Node is_copy(int idx) {
      return _defs._len>ARG_IDX ? null : in(idx);
    }
  }

}
