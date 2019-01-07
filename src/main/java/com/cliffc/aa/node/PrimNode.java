package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

import java.util.HashMap;

// Primitives can be used as an internal operator (their apply() call does the
// primitive operation).  Primitives are wrapped as functions when returned
// from Env lookup, although the immediate lookup+apply is optimized to just
// make a new primitive.  See FunNode for function Node structure.
//
// Fun/Parm-per-arg/Prim/Ret
//

public abstract class PrimNode extends Node {
  public final TypeTuple _targs;
  final Type _ret;
  public final String _name;    // Unique name (and program bits)
  public final String[] _args;  // Handy
  PrimNode( String name, String[] args, TypeTuple targs, Type ret, Node... nodes ) { super(OP_PRIM,nodes); _name=name; _args=args; _targs = targs; _ret = ret; }
  
  final static String[] ARGS1 = new String[]{"x"};
  final static String[] ARGS2 = new String[]{"x","y"};

  public static PrimNode[] PRIMS = new PrimNode[] {
    new RandI64(),
    new Id(TypeOop.OOP), // Pre-split OOP from non-OOP
    new Id(TypeFun.GENERIC_FUN),
    new Id(Type.REAL),
    
    new ConvertInt64F64(),
    new ConvertI64Str(),
    new ConvertF64Str(),
    new ConvertStrStr(),

    new MinusF64(),
    new MinusI64(),
    new   NotI64(),

    new   AddF64(),
    new   SubF64(),
    new   MulF64(),
          
    new   LT_F64(),
    new   LE_F64(),
    new   GT_F64(),
    new   GE_F64(),
    new   EQ_F64(),
    new   NE_F64(),
    
    new   AddI64(),
    new   SubI64(),
    new   MulI64(),

    new   AndI64(),

    new   LT_I64(),
    new   LE_I64(),
    new   GT_I64(),
    new   GE_I64(),
    new   EQ_I64(),
    new   NE_I64(),

    new   EQ_OOP(),
    new   NE_OOP(),
    //new AddStrStr(),
  };

  // Loss-less conversions only, plus int64->flt64 (standard lossy conversion)
  static PrimNode convert( Node actual, Type from, Type to ) {
    if( from.isa(TypeInt.INT64) && to.isa(TypeFlt.FLT64) ) return new ConvertInt64F64(null,actual);
    //if( from==Type.UInt32 && to==Type.I64 ) return convUInt32I64;
    //if( from==Type.UInt32 && to==Type.FLT64 ) return convUInt32F64;
    //if( from==Type. I64 && to==Type.FLT64 ) return  convI64F64;
    if( from.isa(TypeInt.INT64) && to.isa(TypeStr.STR) ) return new ConvertI64Str(null,actual);
    if( from.isa(TypeFlt.FLT64) && to.isa(TypeStr.STR) ) return new ConvertF64Str(null,actual);

    throw AA.unimpl();
  }
  public static PrimNode convertTypeName( Type from, TypeName to, Parse badargs ) {
    return new ConvertTypeName(from,to,badargs);
  }
  public static PrimNode convertTypeNameStruct( TypeStruct from_ts, TypeName to, Parse badargs ) {
    return new ConvertTypeNameStruct(from_ts,to,badargs);
  }
  
  public abstract Type apply( Type[] args ); // Execute primitive
  public boolean is_lossy() { return true; }
  @Override public String xstr() { return _name+"::"+_ret; }
  @Override public Node ideal(GVNGCM gvn) { return null; }
  @Override public Type value(GVNGCM gvn) {
    Type[] ts = new Type[_defs._len];
    for( int i=1; i<_defs._len; i++ ) ts[i] = gvn.type(in(i));
    
    // If any type is below the lowest allowed for this primitive, only return
    // ALL as this is a type error and the program will not type.  E.g. passing
    // Scalar to a float operation.
    for( int i=1; i<_defs._len; i++ )
      if( _targs.at(i-1)!=ts[i] && _targs.at(i-1).isa(ts[i]) )
        return Type.ALL;        // Input is below any valid input
    
    // If any type is above the highest allowed, only return ANY until values fall.
    for( int i=1; i<_defs._len; i++ )
      if( _targs.at(i-1)!=ts[i] && ts[i].isa(_targs.at(i-1).dual()) )
        return Type.ANY;        // Input is above any valid input
    
    // If any type is an unrelated type, e.g. passing a string to a float
    // operation, then again a type error.
    for( int i=1; i<_defs._len; i++ )
      if( !_targs.at(i-1).dual().isa(ts[i]) || !ts[i].isa(_targs.at(i-1)) )
        return Type.ALL;
   
    // If any type is above a constant, assume the result fall to any valid value
    for( int i=1; i<_defs._len; i++ )
      if( ts[i].above_center() )
        return _ret.dual();     // Any valid value
    
    // If any type is below a constant, fall to the lowest valid value
    for( int i=1; i<_defs._len; i++ )
      if( !ts[i].is_con() )
        return _ret;            // Some non-constant input
    
    return apply(ts);           // All constant inputs; constant-fold result
  }
  // Worse-case type for this Node
  @Override public Type all_type() { return _ret; }
  // Prims are equal for same-name-same-signature (and same inputs).
  // E.g. float-minus of x and y is NOT the same as int-minus of x and y
  // despite both names being '-'.
  @Override public int hashCode() { return super.hashCode()+_name.hashCode()+_targs.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof PrimNode) ) return false;
    PrimNode p = (PrimNode)o;
    return _name.equals(p._name) && _targs==p._targs;
  }


  // --------------------  
// Default name constructor using a single tuple type
static class ConvertTypeName extends PrimNode {
  private final Parse _badargs; // Only for converts
  private final HashMap<String,Type> _lex; // Unique lexical scope
  ConvertTypeName(Type from, TypeName to, Parse badargs) { super(to._name,PrimNode.ARGS1,TypeTuple.make(from),to); _lex=to._lex; _badargs=badargs; }
  @Override public Type value(GVNGCM gvn) {
    Type[] ts = new Type[_defs._len];
    for( int i=1; i<_defs._len; i++ )
      ts[i] = gvn.type(_defs.at(i));
    return apply(ts);     // Apply (convert) even if some args are not constant
  }
  @Override public Type apply( Type[] args ) {
    TypeName tn = TypeName.make(_name,_lex,args[1]);
    // If args are illegal, the output is still no worse than _ret in either direction
    return _ret.dual().isa(tn) ? (tn.isa(_ret) ? tn : _ret) : _ret.dual();
  }
  @Override public boolean is_lossy() { return false; }
  @Override public String err(GVNGCM gvn) {
    Type actual = gvn.type(in(1));
    Type formal = _targs.at(0);
    if( !actual.isa(formal) ) // Actual is not a formal
      return _badargs.typerr(actual,formal);
    return null;
  }
}

// Default name constructor using expanded args list
static class ConvertTypeNameStruct extends PrimNode {
  private final Parse _badargs; // Only for converts
  private final HashMap<String,Type> _lex; // Unique lexical scope
  ConvertTypeNameStruct(TypeStruct from, TypeName to, Parse badargs) {
    super(to._name,from._flds,TypeTuple.make(from._ts),to);
    _lex=to._lex;
    _badargs=badargs;
  }
  @Override public Node ideal(GVNGCM gvn) {
    Node nn = gvn.xform(new NewNode(_args,_defs.asAry()));
    TypeStruct ts = TypeStruct.make(_args,_targs._ts);
    TypeName tn = TypeName.make(_name,_lex,ts);
    Node cvt = new ConvertTypeName(ts,tn,_badargs);
    cvt.add_def(null); // Control
    cvt.add_def(nn);
    return cvt;
  }
  
  @Override public Type value(GVNGCM gvn) {
    throw AA.unimpl();
  }
  @Override public Type apply( Type[] args ) {
    throw AA.unimpl();
  }
}

static class ConvertInt64F64 extends PrimNode {
  ConvertInt64F64(Node... nodes) { super("flt64",PrimNode.ARGS1,TypeTuple.INT64,TypeFlt.FLT64,nodes); }
  @Override public TypeFlt apply( Type[] args ) { return TypeFlt.con((double)args[1].getl()); }
  @Override public boolean is_lossy() { return false; }
}

static class ConvertI64Str extends PrimNode {
  ConvertI64Str(Node... nodes) { super("str",PrimNode.ARGS1,TypeTuple.INT64,TypeStr.STR,nodes); }
  @Override public TypeStr apply( Type[] args ) { return TypeStr.con(Long.toString(args[1].getl())); }
  @Override public boolean is_lossy() { return false; }
}

static class ConvertF64Str extends PrimNode {
  ConvertF64Str(Node... nodes) { super("str",PrimNode.ARGS1,TypeTuple.FLT64,TypeStr.STR,nodes); }
  @Override public TypeStr apply( Type[] args ) { return TypeStr.con(Double.toString(args[1].getd())); }
  @Override public boolean is_lossy() { return false; }
}

static class ConvertStrStr extends PrimNode {
  ConvertStrStr(Node... nodes) { super("str",PrimNode.ARGS1,TypeTuple.STR,TypeStr.STR,nodes); }
  @Override public Type apply( Type[] args ) { return args[1]; }
  @Override public Node ideal(GVNGCM gvn) { return in(1); }
  @Override public Type value(GVNGCM gvn) { return gvn.type(in(1)); }
  @Override public boolean is_lossy() { return false; }
}

// 1Ops have uniform input/output types, so take a shortcut on name printing
abstract static class Prim1OpF64 extends PrimNode {
  Prim1OpF64( String name ) { super(name,PrimNode.ARGS1,TypeTuple.FLT64,TypeFlt.FLT64); }
  public TypeFlt apply( Type[] args ) { return TypeFlt.con(op(args[1].getd())); }
  abstract double op( double d );
}

static class MinusF64 extends Prim1OpF64 {
  MinusF64() { super("-"); }
  double op( double d ) { return -d; }
}

// 1Ops have uniform input/output types, so take a shortcut on name printing
abstract static class Prim1OpI64 extends PrimNode {
  Prim1OpI64( String name ) { super(name,PrimNode.ARGS1,TypeTuple.INT64,TypeInt.INT64); }
  public TypeInt apply( Type[] args ) { return TypeInt.con(op(args[1].getl())); }
  abstract long op( long d );
}

static class MinusI64 extends Prim1OpI64 {
  MinusI64() { super("-"); }
  long op( long x ) { return -x; }
}

static class NotI64 extends PrimNode {
  NotI64() { super("!",PrimNode.ARGS1,TypeTuple.INT64,TypeInt.BOOL); }
  public TypeInt apply( Type[] args ) { return args[1].getl()==0?TypeInt.TRUE:TypeInt.FALSE; }
}

// 2Ops have uniform input/output types, so take a shortcut on name printing
abstract static class Prim2OpF64 extends PrimNode {
  Prim2OpF64( String name ) { super(name,PrimNode.ARGS2,TypeTuple.FLT64_FLT64,TypeFlt.FLT64); }
  public TypeFlt apply( Type[] args ) { return TypeFlt.con(op(args[1].getd(),args[2].getd())); }
  abstract double op( double x, double y );
}

static class AddF64 extends Prim2OpF64 {
  AddF64() { super("+"); }
  double op( double l, double r ) { return l+r; }
  @Override public byte op_prec() { return 5; }
}

static class SubF64 extends Prim2OpF64 {
  SubF64() { super("-"); }
  double op( double l, double r ) { return l-r; }
  @Override public byte op_prec() { return 5; }
}

static class MulF64 extends Prim2OpF64 {
  MulF64() { super("*"); }
  double op( double l, double r ) { return l*r; }
  @Override public byte op_prec() { return 6; }
}

// 2RelOps have uniform input types, and bool output
abstract static class Prim2RelOpF64 extends PrimNode {
  Prim2RelOpF64( String name ) { super(name,PrimNode.ARGS2,TypeTuple.FLT64_FLT64,TypeInt.BOOL); }
  public TypeInt apply( Type[] args ) { return op(args[1].getd(),args[2].getd())?TypeInt.TRUE:TypeInt.FALSE; }
  abstract boolean op( double x, double y );
  @Override public byte op_prec() { return 4; }
}

static class LT_F64 extends Prim2RelOpF64 { LT_F64() { super("<" ); } boolean op( double l, double r ) { return l< r; } }
static class LE_F64 extends Prim2RelOpF64 { LE_F64() { super("<="); } boolean op( double l, double r ) { return l<=r; } }
static class GT_F64 extends Prim2RelOpF64 { GT_F64() { super(">" ); } boolean op( double l, double r ) { return l> r; } }
static class GE_F64 extends Prim2RelOpF64 { GE_F64() { super(">="); } boolean op( double l, double r ) { return l>=r; } }
static class EQ_F64 extends Prim2RelOpF64 { EQ_F64() { super("=="); } boolean op( double l, double r ) { return l==r; } }
static class NE_F64 extends Prim2RelOpF64 { NE_F64() { super("!="); } boolean op( double l, double r ) { return l!=r; } }


// 2Ops have uniform input/output types, so take a shortcut on name printing
abstract static class Prim2OpI64 extends PrimNode {
  Prim2OpI64( String name ) { super(name,PrimNode.ARGS2,TypeTuple.INT64_INT64,TypeInt.INT64); }
  public TypeInt apply( Type[] args ) { return TypeInt.con(op(args[1].getl(),args[2].getl())); }
  abstract long op( long x, long y );
}

static class AddI64 extends Prim2OpI64 {
  AddI64() { super("+"); }
  long op( long l, long r ) { return l+r; }
  @Override public byte op_prec() { return 5; }
}

static class SubI64 extends Prim2OpI64 {
  SubI64() { super("-"); }
  long op( long l, long r ) { return l-r; }
  @Override public byte op_prec() { return 5; }
}

static class MulI64 extends Prim2OpI64 {
  MulI64() { super("*"); }
  long op( long l, long r ) { return l*r; }
  @Override public byte op_prec() { return 6; }
}

static class AndI64 extends Prim2OpI64 {
  AndI64() { super("&"); }
  long op( long l, long r ) { return l&r; }
  @Override public byte op_prec() { return 7; }
}

// 2RelOps have uniform input types, and bool output
abstract static class Prim2RelOpI64 extends PrimNode {
  Prim2RelOpI64( String name ) { super(name,PrimNode.ARGS2,TypeTuple.INT64_INT64,TypeInt.BOOL); }
  public TypeInt apply( Type[] args ) { return op(args[1].getl(),args[2].getl())?TypeInt.TRUE:TypeInt.FALSE; }
  abstract boolean op( long x, long y );
  @Override public byte op_prec() { return 4; }
}

static class LT_I64 extends Prim2RelOpI64 { LT_I64() { super("<" ); } boolean op( long l, long r ) { return l< r; } }
static class LE_I64 extends Prim2RelOpI64 { LE_I64() { super("<="); } boolean op( long l, long r ) { return l<=r; } }
static class GT_I64 extends Prim2RelOpI64 { GT_I64() { super(">" ); } boolean op( long l, long r ) { return l> r; } }
static class GE_I64 extends Prim2RelOpI64 { GE_I64() { super(">="); } boolean op( long l, long r ) { return l>=r; } }
static class EQ_I64 extends Prim2RelOpI64 { EQ_I64() { super("=="); } boolean op( long l, long r ) { return l==r; } }
static class NE_I64 extends Prim2RelOpI64 { NE_I64() { super("!="); } boolean op( long l, long r ) { return l!=r; } }


static class EQ_OOP extends PrimNode {
  EQ_OOP() { super("==",PrimNode.ARGS2,TypeTuple.OOP_OOP,TypeInt.BOOL); }
  public TypeInt apply( Type[] args ) { return args[1] == args[2] ? TypeInt.TRUE : TypeInt.FALSE; }
  @Override public byte op_prec() { return 4; }
}

static class NE_OOP extends PrimNode {
  NE_OOP() { super("!=",PrimNode.ARGS2,TypeTuple.OOP_OOP,TypeInt.BOOL); }
  public TypeInt apply( Type[] args ) { return args[1] != args[2] ? TypeInt.TRUE : TypeInt.FALSE; }
  @Override public byte op_prec() { return 4; }
}


static class RandI64 extends PrimNode {
  RandI64() { super("math_rand",PrimNode.ARGS1,TypeTuple.INT64,TypeInt.INT64); }
  @Override public TypeInt apply( Type[] args ) { return TypeInt.con(new java.util.Random().nextInt((int)args[1].getl())); }
  @Override public Type value(GVNGCM gvn) { return gvn.type(in(1)).meet(TypeInt.FALSE); }
  // Rands have hidden internal state; 2 Rands are never equal
  @Override public boolean equals(Object o) { return this==o; }
}

static class Id extends PrimNode {
  Id(Type arg) { super("id",PrimNode.ARGS1,TypeTuple.make(arg),arg); }
  @Override public Type apply( Type[] args ) { return args[1]; }
  @Override public Node ideal(GVNGCM gvn) { return in(1); }
  @Override public Type value(GVNGCM gvn) { return gvn.type(in(1)); }
}

static class AddStrStr extends PrimNode {
  AddStrStr( ) { super("+",PrimNode.ARGS2,TypeTuple.STR_STR,TypeStr.STR); }
  public TypeStr apply( Type[] args ) { return TypeStr.con(args[1].getstr()+args[2].getstr()); }
}

}
