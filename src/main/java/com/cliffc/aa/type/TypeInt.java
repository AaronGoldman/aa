package com.cliffc.aa.type;

import com.cliffc.aa.util.SB;
import com.cliffc.aa.util.VBitSet;

import java.util.HashMap;
import java.util.function.Predicate;

import static com.cliffc.aa.AA.unimpl;

public class TypeInt extends Type<TypeInt> {
  public long _lo, _hi;         // int or long con according
  private TypeInt ( long lo, long hi ) { super(TINT); init(lo,hi); }
  private void init(long lo, long hi ) { super.init(TINT); _lo=lo; _hi=hi; }
  // Hash does not depend on other types
  @Override int compute_hash() { return super.compute_hash()+(int)(_lo+_hi); }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeInt) ) return false;
    TypeInt t2 = (TypeInt)o;
    return super.equals(o) && _lo==t2._lo && _hi==t2._hi;
  }
  @Override public boolean cycle_equals( Type o ) { return equals(o); }
  @Override public SB str( SB sb, VBitSet dups, TypeMem mem, boolean debug ) {
    sb.p(_name);
    if( _lo==_hi ) return sb.p(_lo);
    if( this==INT64 )        return sb.p( "int64");
    if( this==INT64.dual() ) return sb.p("~int64");
    if( this==INT32 )        return sb.p( "int32");
    if( this==INT32.dual() ) return sb.p("~int32");
    if( this==INT16 )        return sb.p( "int16");
    if( this==INT16.dual() ) return sb.p("~int16");
    if( this==INT8  )        return sb.p( "int8" );
    if( this==INT8 .dual() ) return sb.p("~int8" );
    if( this==INT1  )        return sb.p( "bool" );
    if( this==INT1 .dual() ) return sb.p("~bool" );
    if( _lo < _hi ) return sb.p('[').p(_lo).p('-').p(_hi).p(']');
    return sb.p("[-inf-").p(_hi).p(',').p(_lo).p("-+inf]");
  }
  private static TypeInt FREE=null;
  @Override protected TypeInt free( TypeInt ret ) { FREE=this; return ret; }
  public static TypeInt make( long lo, long hi ) {
    TypeInt t1 = FREE;
    if( t1 == null ) t1 = new TypeInt(lo,hi);
    else {  FREE = null;      t1.init(lo,hi); }
    TypeInt t2 = (TypeInt)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }
  public static TypeInt con(long con) { return make(con,con); }


  static public  final TypeInt INT64 = make(Long   .MIN_VALUE,Long   .MAX_VALUE);
  static public  final TypeInt INT32 = make(Integer.MIN_VALUE,Integer.MAX_VALUE);
  static public  final TypeInt INT16 = make(-32768,32767);
  static public  final TypeInt INT8  = make(0,255);
  static public  final TypeInt INT1  = make(0,1);
  static public  final TypeInt ZERO  = make(0,0);
  static public  final TypeInt BOOL  = INT1;
  static public  final TypeInt TRUE  = make(1,1);
  static public  final Type    FALSE = ZERO;
  static public  final TypeInt NINT8 = make(1,255);
  static public  final TypeInt NINT64= INT64; // TODO
  static final TypeInt[] TYPES = new TypeInt[]{INT64,INT32,INT16,INT8,BOOL,TRUE,con(3),con(1L<<54),NINT8};
  static void init1( HashMap<String,Type> types ) {
    types.put("bool" ,BOOL);
    types.put("int1" ,BOOL);
    types.put("int8" ,INT8);
    types.put("int16",INT16);
    types.put("int32",INT32);
    types.put("int64",INT64);
    types.put("int"  ,INT64);
  }
  // Return a long from a TypeInt constant; assert otherwise.
  @Override public boolean is_con() { return _lo==_hi; }
  @Override public double getd() { assert is_con() && (long)((double)_lo)==_lo; return (double)_lo; }
  @Override public long   getl() { assert is_con(); return _lo; }

  @Override protected TypeInt xdual() { return is_con() ? this : new TypeInt(_hi,_lo); }
  @Override protected Type xmeet( Type t ) {
    assert t != this;
    switch( t._type ) {
    case TINT:   break;
    case TFLT:   return xmeetf((TypeFlt)t);
    case TFUNPTR:
    case TMEMPTR:
    case TRPC:   return cross_nil(t);
    case TFUNSIG:
    case TARY:
    case TLIVE:
    case TOBJ:
    case TSTR:
    case TSTRUCT:
    case TTUPLE:
    case TMEM:   return ALL;
    default: throw typerr(t);
    }
    TypeInt tf = (TypeInt)t;
    // Handle the 64-bit top/bot endpoints
    return make(Math.min(_lo,tf._lo),Math.max(_hi,tf._hi));
  }

  // int meet float
  Type xmeetf( TypeFlt tf ) {
    // TODO: allow small integers & precise floats overlaps
    
    //if( above_center() ) {      // High int
    //  if( tf.above_center() ) { // High float; choices abound
    //    return make((long)Math.min(_lo,tf._lo),(long)Math.max(_hi,tf._hi));
    //  } else {                  // High int meet low float.  See if any ints overlap
    //    return _lo < tf._lo ||  _hi > tf._hi || !tf.includes_int()? Type.SCALAR : tf;
    //  }
    //} else {                    // Low int
    //  if( tf.above_center() ) { // Low float; restrictions abount
    //    return tf._hi < _lo ||  tf._lo > _hi || !tf.includes_int() ? Type.SCALAR : this;
    //  } else {                  // High int meet low float.  See if any ints overlap
    //    return TypeFlt.make(Math.min(_lo,tf._lo),Math.max(_hi,tf._hi));
    //  }
    //}
    return Type.SCALAR;
  }
  
  @Override public boolean above_center() { return _hi < _lo; }
  @Override public boolean may_be_con() { return _hi <= _lo; }
  @Override public boolean must_nil() { return _lo <= 0 && 0 <= _hi; }
  @Override public boolean  may_nil() { return _hi <= 0 && 0 <= _lo; }
  @Override Type not_nil() { return this; }
  @Override public Type meet_nil(Type nil) {
    //if( _x==2 ) return nil;
    //if( _x==0 && _con==0 ) return nil==Type.XNIL ? this : Type.NIL;
    //return TypeInt.make(-2,_z,0);
    throw unimpl();
  }

  // Lattice of conversions:
  // -1 unknown; top; might fail, might be free (Scalar->Int); Scalar might lift
  //    to e.g. Float and require a user-provided rounding conversion from F64->Int.
  //  0 requires no/free conversion (Int8->Int64, F32->F64)
  // +1 requires a bit-changing conversion (Int->Int)
  // 99 Bottom; No free converts; e.g. Int->Int requires explicit rounding
  @Override public byte isBitShape(Type t) {
    // TODO: Allow loss-less conversions (e.g. small float integer constants convert to ints just fine)
    if( t._type == Type.TFLT ) throw unimpl(); // small int constants -> float is free
    if( t._type == Type.TINT ) return 0; // Int->Int free
    if( t._type == Type.TMEMPTR ) return 99; // No int->ptr conversion
    if( t._type == Type.TFUNPTR ) return 99; // No int->ptr conversion
    if( t._type == Type.TREAL ) return 1;
    if( t._type == TSCALAR ) return 9; // Might have to autobox
    throw com.cliffc.aa.AA.unimpl();
  }
  @Override public Type widen() { return INT64; }
  @Override void walk( Predicate<Type> p ) { p.test(this); }
  public TypeInt minsize(TypeInt ti) { return make(Math.max(_lo,ti._lo),Math.min(_hi,ti._hi)); }
  public TypeInt maxsize(TypeInt ti) { return make(Math.min(_lo,ti._lo),Math.max(_hi,ti._hi)); }
}
