package com.cliffc.aa;

public class TypeInt extends Type {
  byte _x;                // -1 bot, 0 con, +1 top
  byte _z;                // bitsiZe, one of: 1,8,16,32,64
  long _con;              // only if _x==0
  private TypeInt( int x, int z, long con ) { super(TINT); init(x,z,con); }
  private void init(int x, int z, long con ) { _x=(byte)x; _z=(byte)z; _con = con; }
  @Override public int hashCode( ) { return TINT+_x+_z+(int)_con;  }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeInt) ) return false;
    TypeInt t2 = (TypeInt)o;
    return _x==t2._x && _z==t2._z && _con==t2._con;
  }
  @Override public String toString() {
    if( _x==0 ) return Long.toString(_con);
    return (_x==1?"~":"")+"Int"+Integer.toString(_z);
  }
  private static TypeInt FREE=null;
  private TypeInt free( TypeInt f ) { FREE=f; return this; }
  public static TypeInt make( int x, int z, long con ) {
    TypeInt t1 = FREE;
    if( t1 == null ) t1 = new TypeInt(x,z,con);
    else { FREE = null; t1.init(x,z,con); }
    TypeInt t2 = (TypeInt)t1.hashcons();
    return t1==t2 ? t1 : t2.free(t1);
  }
  public static TypeInt con(long con) { return make(0,log(con),con); }

  static public final TypeInt  INT64 = make(-1,64,0);
  static public final TypeInt  INT32 = make(-1,32,0);
  static public final TypeInt  INT16 = make(-1,16,0);
  static public final TypeInt  INT8  = make(-1, 8,0);
  static public final TypeInt  BOOL  = make(-1, 1,0);
  static public final TypeInt TRUE   = make( 0, 1,1);
  static public final TypeInt FALSE  = make( 0, 1,0);
  static public final TypeInt XINT1  = make( 1, 1,0);
  static final TypeInt[] TYPES = new TypeInt[]{INT64,INT32,INT16,BOOL,TRUE,FALSE,XINT1};
  // Return a long from a TypeInt constant; assert otherwise.
  @Override public long getl() { assert is_con(); return _con; }

  @Override protected TypeInt xdual() { return _x==0 ? this : new TypeInt(-_x,_z,_con); }
  @Override protected Type xmeet( Type t ) {
    if( t == this ) return this;
    switch( t._type ) {
    case TINT:   break;
    case TFLT:   return xmeetf((TypeFlt)t);
    case TSTR:   return TypeUnion.make(false,TypeStr.STR,this);
    case TFUN:   return Type.SCALAR;
    case TERROR: return ((TypeErr)t)._all ? t : this;
    case TCONTROL:
    case TTUPLE: return TypeErr.ALL;
    case TUNION: return t.xmeet(this); // Let TypeUnion decide
    default: throw typerr(t);
    }
    TypeInt tt = (TypeInt)t;
    if( _x==tt._x && _z==tt._z && _con==tt._con ) return this;
    int maxz = Math.max(_z,tt._z);
    int minz = Math.min(_z,tt._z);
    if( _x== 0 && tt._x== 0 && _con==tt._con ) return make(0,maxz,_con);
    if( _x<= 0 && tt._x<= 0 ) return make(-1,maxz,0); // Both bottom, widen size
    if( _x==-1 && tt._x== 1 ) return this; // Top loses to other guy
    if( _x== 1 && tt._x==-1 ) return tt;   // Top loses to other guy
    if( _x== 1 && tt._x== 1 ) return make( 1,minz,0); // Both top, narrow size

    // constant meet top
    // _x==1 ==> tt is the constant, else _x==0 and is the constant
    TypeInt tcon = _x==1 ? tt : this;
    TypeInt ttop = _x==1 ? this : tt;
    if( log(tcon._con) <= ttop._z ) return tcon;
    return make(-1,maxz,0);
  }

  private static int log( long con ) {
    if(     0 <= con && con <=     1 ) return  1;
    if(  -128 <= con && con <=   127 ) return  8;
    if(-32768 <= con && con <= 32767 ) return 16;
    if(Integer.MIN_VALUE <= con && con <= Integer.MAX_VALUE ) return 32;
    return 64;
  }
  private static TypeInt sz( int log ) {
    switch( log ) {
    case 1:
    case 8:  return INT8 ;
    case 16: return INT16;
    case 32: return INT32;
    case 64: return INT64;
    default: throw AA.unimpl();
    }
  }
  
  Type xmeetf( TypeFlt tf ) {
    if( _x == 1 ) {               // Top Int, size 1 to 64
      if( tf._x== -1 ) return tf; // (~Int | Flt) = Flt // choice includes 0 which is all flts
      if( tf._x==  1 ) // ~Int | ~Flt = ~Int of some type; defaults to Int tossing all the non-integral Flt choices
        return make(1,Math.min(tf._z>>1,_z),0);     // Choices limited to smaller-ints (to fit in float of same range)
      // Float constant: cast "for free" to Int constant if possible, else fall to same as Flt-bottom
      long con = (long)tf._con;
      // Fits in the int choices, just keep float, but could return the int constant just as well
      if( con == tf._con && log(con) <= _z )  return tf; 
      return TypeFlt.make(-1,TypeFlt.log(tf._con),0);      // Not an int constant or too large
    }

    if( _x == 0 ) {             // Constant int
      int lg = log(_con);
      if( tf._x== -1 )          // Bottom float/double
        return widen(lg,tf._z); // Wider of (ints-wider-by-1) and floats
      if( tf._x== 0 ) {         // Int constant vs Float constant
        if( _con==tf._con ) return this; // Matching int constant wins
        if( ((long)tf._con) == tf._con ) // Float is a integer
          return xmeet(TypeInt.con((long)tf._con)); // Return as-if meeting 2 integers
        return tf.xmeet(TypeFlt.con(_con));         // Return as-if meeting 2 floats
      }
    } // Fall into the bottom-int case

    // Bottom Int, size 1 to 64
    if( tf._x== 1 ) return this; // ( Int | ~Flt) = Int, since can choose 0.0
    // Float constant: cast "for free" to Int if possible, else fall to same as Flt-bottom
    long con = (long)tf._con;
    if( tf._x== 0 && con == tf._con )  
      return make(-1,Math.max(_z,log(con)),0);
    if( tf._x<= 0 && (_z<<1) <= tf._z ) return TypeFlt.make(-1,tf._z,0); // Fits in a float
    if( tf._x== 0 ) tf = TypeFlt.log(tf._con)==32 ? TypeFlt.FLT32 : TypeFlt.FLT64;
    return TypeUnion.make(false,this,tf);
  }

  private static Type widen(int isz, int fsz) {
    if( (isz<<1) <= fsz ) return TypeFlt.make(-1,fsz,0); // Fits in a float
    return TypeUnion.make(false,make(-1,isz,0),TypeFlt.make(-1,fsz,0));
  }
    
  // Lattice of conversions:
  // -1 unknown; top; might fail, might be free (Scalar->Int); Scalar might lift
  //    to e.g. Float and require a user-provided rounding conversion from F64->Int.
  //  0 requires no/free conversion (Int8->Int64, F32->F64)
  // +1 requires a bit-changing conversion (Int->Flt)
  // 99 Bottom; No free converts; e.g. Flt->Int requires explicit rounding
  @Override public byte isBitShape(Type t) {
    // TODO: Allow loss-less conversions (e.g. small float integer constants convert to ints just fine)
    if( t._type == Type.TINT ) return (byte)(_z<=((TypeInt)t)._z ? 0 : 99);
    if( t._type == Type.TFLT ) return 1; // Int->Flt ignores large int overflow issues
    throw AA.unimpl();
  }
  @Override public Type widen() {
    assert _x <= 0;
    return INT64;
  }
  @Override public boolean above_center() { return _x>0; }
  @Override public boolean canBeConst() { return _x>=0; }
  @Override public boolean is_con()   { return _x==0; }
}
