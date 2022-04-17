package com.cliffc.aa.type;

import com.cliffc.aa.util.*;

import java.util.HashMap;

public class TypeFlt extends Type<TypeFlt> {
  byte _x;                // -2 bot, -1 not-null, 0 con, +1 not-null-top +2 top
  byte _z;                // bitsiZe, one of: 32,64
  double _con;
  private TypeFlt init(int x, int z, double con ) { super.init(); _x=(byte)x; _z=(byte)z; _con = con; return this; }
  @Override TypeFlt copy() { return _copy().init(_x,_z,_con); }
  // Hash does not depend on other types
  @Override long static_hash() { return Util.mix_hash(super.static_hash(),_x,_z,(int)_con); }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeFlt t2) ) return false;
    return super.equals(o) && _x==t2._x && _z==t2._z && _con==t2._con;
  }
  @Override public boolean cycle_equals( Type o ) { return equals(o); }
  @Override SB _str0( VBitSet visit, NonBlockingHashMapLong<String> dups, SB sb, boolean debug, boolean indent ) {
    if( _x==0 )
      return ((float)_con)==_con ? sb.p((float)_con).p('f') : sb.p(_con);
    return sb.p(_x>0?"~":"").p(Math.abs(_x)==1?"n":"").p("flt").p(_z);
  }

  static Type valueOfFlt(String cid) {
    return switch(cid) {
    case "flt64"  ->  FLT64;
    case "nflt32" -> NFLT32;
    case "nflt64" -> NFLT64;
    default       -> null;
    };
  }

  static { new Pool(TFLT,new TypeFlt()); }
  public static TypeFlt make( int x, int z, double con ) {
    //if( x==0 && (double)((long)con)==con ) return TypeInt.con((long)con);
    TypeFlt t1 = POOLS[TFLT].malloc();
    return t1.init(x,z,con).hashcons_free();
  }

  public static TypeFlt con(double con) { return make(0,log(con),con); }

  public static final TypeFlt FLT64 = make(-2,64,0);
  public static final TypeFlt FLT32 = make(-2,32,0);
  public static final TypeFlt PI    = con(Math.PI);
  public static final TypeFlt HALF  = con(0.5);
  public static final TypeFlt NFLT64= make(-1,64,0);
  public static final TypeFlt NFLT32= make(-1,32,0);
  public static final TypeFlt[] TYPES = new TypeFlt[]{FLT64,NFLT64,PI,NFLT32,FLT32,HALF};
  static void init1( HashMap<String,Type> types ) {
    types.put("flt32",FLT32);
    types.put("flt64",FLT64);
    types.put("flt"  ,FLT64);
  }
  // Return a double from a TypeFlt constant; assert otherwise.
  @Override public double getd() { assert is_con(); return _con; }
  //@Override public long   getl() { assert is_con() && ((long)_con)==_con; return (long)_con; }

  @Override protected TypeFlt xdual() { return _x==0 ? this : POOLS[TFLT].<TypeFlt>malloc().init(-_x,_z,_con); }
  @Override protected Type xmeet( Type t ) {
    assert t != this;
    switch( t._type ) {
    case TFLT:   break;
    case TINT:   //return ((TypeInt)t).xmeetf(this); // Not a lattice?
    case TFUNPTR:
    case TMEMPTR:
    case TSTRUCT:
    case TRPC:   return cross_nil(t);
    case TARY:
    case TFLD:
    case TTUPLE:
    case TMEM:   return ALL;
    default: throw typerr(t);
    }
    TypeFlt tf = (TypeFlt)t;
    assert !equals(tf);         // Already covered by interning
    int maxz = Math.max(_z,tf._z);
    int minz = Math.min(_z,tf._z);
    if( _x== 0 && tf._x== 0 && _con==tf._con ) return make(0,maxz,_con);
    if( _x<= 0 && tf._x<= 0 ) return make(Math.min(nn(),tf.nn()),maxz,0); // Both bottom, widen size
    if( _x > 0 && tf._x > 0 ) return make(Math.min(_x,tf._x),minz,0); // Both top, narrow size
    if( _x==-2 && tf._x== 2 ) return this; // Top loses to other guy
    if( _x== 2 && tf._x==-2 ) return tf;   // Top loses to other guy

    // ttop==+1,+2 and that is 0,-1,-2
    TypeFlt that = _x>0 ? tf : this;
    TypeFlt ttop = _x>0 ? this : tf;
    if( that._x<0 ) return that; // Return low value
    if( log(that._con) <= ttop._z && (that._con!=0 || ttop._x==2) )
      return that;            // Keep a fitting constant
    return make(that.nn(),that._z,0); // No longer a constant
  }
  private int nn() { assert _x <=0; return _con!=0 || _x== -1 ? -1 : -2; }
  static int log( double con ) { return ((double)(float)con)==con ? 32 : 64; }

  @Override public boolean above_center() { return _x>0; }
  @Override public boolean may_be_con() { return _x>=0; }
  @Override public boolean is_con()   { return _x==0; }
  @Override public boolean must_nil() { return _x==-2; }
  @Override public boolean  may_nil() { return _x== 2; }
  @Override TypeFlt not_nil() { return _x==2 ? make(1,_z,_con) : this; }
  @Override public Type meet_nil(Type nil) {
    if( nil==Type.XNIL )
      return _x==2 ? Type.XNIL : (_x==-2 ? Type.SCALAR : Type.NSCALR);
    return TypeFlt.make(-2,_x<=0?_z:32,0);
  }

  @Override public Type _widen() {
    if( _x> 0 ) return this;
    if( _x==0 ) return _con==0 ? FLT64 : NFLT64;
    return make(_x,64,0);
  }
}
