package com.cliffc.aa.type;

import com.cliffc.aa.node.FunNode;

/** A Tuple with exactly 4 fields:
 *  0 - Function exit control
 *  1 - Function exit value type
 *  2 - Function RPC type (set of callers) - Short cut available type, to avoid
 *      going to the FunNode and reversing to the RPC.
 *  3 - Function signature, with a single FIDX 
 * 
 *  This is the type of EpilogNodes, except they also have a _fidx to map to
 *  the FunNode (used when the FunNode is collapsing) AND a pointer to the
 *  FunNode.
*/
public class TypeFun extends TypeTuple<TypeFun> {
  private TypeFun( Type[] ts, Type inf ) {
    super(TFUN, inf.above_center(), ts,inf);
    init(ts,inf);
  }
  protected void init( Type[] ts, Type inf ) {
    super.init(TFUN, inf.above_center(), ts,inf);
    assert is_fun();
  }
  
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    return o instanceof TypeFun && eq((TypeFun)o);
  }    
  
  private static TypeFun FREE=null;
  @Override protected TypeFun free( TypeFun ret ) { FREE=this; return ret; }
  private static TypeFun make( Type[] ts, Type inf ) {
    TypeFun t1 = FREE;
    if( t1 == null ) t1 = new TypeFun(ts,inf);
    else { FREE = null; t1.init(ts,inf); }
    TypeFun t2 = (TypeFun)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }
  
  public static TypeFun make( Type ctrl, Type ret, TypeRPC rpc, TypeFunPtr fun ) {
    return make(new Type[]{ctrl,ret,rpc,fun}, Type.ALL);
  }
  public static TypeFun make( TypeFunPtr fun ) { return make(Type.CTRL,fun._ret,TypeRPC.ALL_CALL, fun); }
  public static TypeFun make( int fidx ) { return make(FunNode.find_fidx(fidx)._tf); }

  public static final TypeFun FUN1        = make(TypeFunPtr.any(1, 0)); // Some 1-arg function
  public static final TypeFun FUN2        = make(TypeFunPtr.any(2,-1)); // Some 2-arg function
  public static final TypeFun GENERIC_FUN = make(TypeFunPtr.GENERIC_FUNPTR); // For EpilogNode default type
  static final TypeFun[] TYPES = new TypeFun[]{FUN1,FUN2};
  
  // The length of Tuples is a constant, and so is its own dual.  Otherwise
  // just dual each element.  Also flip the infinitely extended tail type.
  @Override protected TypeFun xdual() {
    Type[] ts = new Type[_ts.length];
    for( int i=0; i<_ts.length; i++ ) ts[i] = _ts[i].dual();
    return new TypeFun(ts,_inf.dual());
  }
  // Standard Meet.
  @Override protected Type xmeet( Type t ) {
    switch( t._type ) {
    case TFUN: break;
    case TSTR:
    case TFLT:
    case TINT:
    case TFUNPTR:
    case TOOP:
    case TSTRUCT:
    case TTUPLE: 
    case TRPC:   return Type.SCALAR;
    case TNIL:
    case TNAME:  return t.xmeet(this); // Let other side decide
    default: throw typerr(t);
    }
    // Length is longer of 2 tuples.  Shorter elements take the meet; longer
    // elements meet the shorter extension.
    TypeFun tt = (TypeFun)t;
    assert _ts.length==tt._ts.length;
    Type[] ts = new Type[4];
    for( int i=0; i<_ts.length; i++ )  ts[i] = _ts[i].meet(tt._ts[i]);
    Type inf = _inf.meet(tt._inf);
    return make(ts,inf);
  }

  // Return true if this is a forward-ref function pointer (return type from EpilogNode)
  @Override public boolean is_forward_ref() { return fun().is_forward_ref(); }

  public Type ctl() { return _ts[0]; }
  public Type val() { return _ts[1]; }
  public TypeFunPtr fun() { return (TypeFunPtr)_ts[3]; }
  // Return an error message, if any exists
  @Override public String errMsg() {
    // Ok to have a function which cannot be executed
    return null;
  }
}
