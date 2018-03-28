package com.cliffc.aa.node;

import com.cliffc.aa.*;

public abstract class PrimNode extends ConNode<TypeFun> {
  public final String _name;    // Unique name (and program bits)
  private final String[] _args;  // Handy
  PrimNode( String name, String[] args, TypeFun tf ) { super(tf); _name=name; _args=args; }
  
  final static String[] ARGS1 = new String[]{"x"};
  final static String[] ARGS2 = new String[]{"x","y"};

  public static PrimNode[] PRIMS = new PrimNode[] {
    new ConvertInt32Flt64(),

    new MinusFlt64(),
    new MinusInt64(),
    new   NotInt64(),

    new   AddFlt64(),
    new   SubFlt64(),
    new   MulFlt64(),
          
    new   AddInt64(),
    new   SubInt64(),
    new   MulInt64(),
  };

  // Loss-less conversions only
  static PrimNode convert( Type from, Type to ) {
    if( from.isa(TypeInt.INT32) && to.isa(TypeFlt.FLT64) ) return new ConvertInt32Flt64();
    //if( from==Type.UInt32 && to==Type.Int64 ) return convUInt32Int64;
    //if( from==Type.UInt32 && to==Type.FLT64 ) return convUInt32Flt64;
    //if( from==Type. Int64 && to==Type.FLT64 ) return  convInt64Flt64;
    throw AA.unimpl();
  }
  
  public abstract Type apply( Type[] args ); // Execute primitive
  public boolean is_lossy() { return true; }
  @Override public String str() { return _name+"::"+_t._ret; }
}

class ConvertInt32Flt64 extends PrimNode {
  ConvertInt32Flt64() { super("flt64",PrimNode.ARGS1,TypeFun.make(TypeTuple.INT32,TypeFlt.FLT64)); }
  @Override public TypeFlt apply( Type[] args ) { return TypeFlt.make(0,64,(double)args[1].getl()); }
  @Override public int op_prec() { return 9; }
  public boolean is_lossy() { return false; }
}

// 1Ops have uniform input/output types, so take a shortcut on name printing
abstract class Prim1OpF64 extends PrimNode {
  Prim1OpF64( String name ) { super(name,PrimNode.ARGS1,TypeFun.FLT64); }
  public TypeFlt apply( Type[] args ) { return TypeFlt.make(0,64,op(args[1].getd())); }
  abstract double op( double d );
  @Override public int op_prec() { return 9; }
}

class MinusFlt64 extends Prim1OpF64 {
  MinusFlt64() { super("-"); }
  double op( double d ) { return -d; }
}

// 1Ops have uniform input/output types, so take a shortcut on name printing
abstract class Prim1OpI64 extends PrimNode {
  Prim1OpI64( String name ) { super(name,PrimNode.ARGS1,TypeFun.INT64); }
  public TypeInt apply( Type[] args ) { return TypeInt.con(op(args[1].getl())); }
  @Override public int op_prec() { return 9; }
  abstract long op( long d );
}

class MinusInt64 extends Prim1OpI64 {
  MinusInt64() { super("-"); }
  long op( long x ) { return -x; }
}

class NotInt64 extends PrimNode {
  NotInt64() { super("!",PrimNode.ARGS1,TypeFun.make(TypeTuple.INT64,TypeInt.BOOL)); }
  public TypeInt apply( Type[] args ) { return args[1].getl()==0?TypeInt.TRUE:TypeInt.FALSE; }
  @Override public int op_prec() { return 9; }
}

// 2Ops have uniform input/output types, so take a shortcut on name printing
abstract class Prim2OpF64 extends PrimNode {
  Prim2OpF64( String name ) { super(name,PrimNode.ARGS2,TypeFun.FLT64_FLT64); }
  public TypeFlt apply( Type[] args ) { return TypeFlt.make(0,64,op(args[1].getd(),args[2].getd())); }
  abstract double op( double x, double y );
}

class AddFlt64 extends Prim2OpF64 {
  AddFlt64() { super("+"); }
  double op( double l, double r ) { return l+r; }
  @Override public int op_prec() { return 5; }
}

class SubFlt64 extends Prim2OpF64 {
  SubFlt64() { super("-"); }
  double op( double l, double r ) { return l-r; }
  @Override public int op_prec() { return 5; }
}

class MulFlt64 extends Prim2OpF64 {
  MulFlt64() { super("*"); }
  double op( double l, double r ) { return l*r; }
  @Override public int op_prec() { return 6; }
}

// 2Ops have uniform input/output types, so take a shortcut on name printing
abstract class Prim2OpI64 extends PrimNode {
  Prim2OpI64( String name ) { super(name,PrimNode.ARGS2,TypeFun.INT64_INT64); }
  public TypeInt apply( Type[] args ) { return TypeInt.con(op(args[1].getl(),args[2].getl())); }
  abstract long op( long x, long y );
}

class AddInt64 extends Prim2OpI64 {
  AddInt64() { super("+"); }
  long op( long l, long r ) { return l+r; }
  @Override public int op_prec() { return 5; }
}

class SubInt64 extends Prim2OpI64 {
  SubInt64() { super("-"); }
  long op( long l, long r ) { return l-r; }
  @Override public int op_prec() { return 5; }
}

class MulInt64 extends Prim2OpI64 {
  MulInt64() { super("*"); }
  long op( long l, long r ) { return l*r; }
  @Override public int op_prec() { return 6; }
}
