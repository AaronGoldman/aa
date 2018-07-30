package com.cliffc.aa.type;

import com.cliffc.aa.AA;
import java.util.HashMap;

public class TypeStr extends TypeNullable {
  byte _x;                // -1 bot, 1 con, 0 top
  String _con;            // 
  private TypeStr ( byte nil, int x, String con ) { super(TSTR,nil); init(nil,x,con); }
  private void init(byte nil, int x, String con ) {
    super.init(nil);
    assert con != null;
    assert (nil==IS_NIL && _x==1)||(nil!=IS_NIL);
    _x=(byte)x;
    _con = con;
    assert _type==TSTR;
  }
  @Override public int hashCode( ) { return super.hashCode()+_x+_con.hashCode();  }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeStr) ) return false;
    TypeStr t2 = (TypeStr)o;
    return super.eq(t2) && _x==t2._x && _con.equals(t2._con);
  }
  @Override public String toString() {
    return String.format(TSTRS[_nil],(_x==0?"~":"")+_con);
  }
  private static TypeStr FREE=null;
  private TypeStr free( TypeStr f ) { assert f._type==TSTR; FREE=f; return this; }
  public static TypeStr make( byte nil, int x, String con ) {
    TypeStr t1 = FREE;
    if( t1 == null ) t1 = new TypeStr(nil,x,con);
    else { FREE = null; t1.init(nil,x,con); }
    TypeStr t2 = (TypeStr)t1.hashcons();
    return t1==t2 ? t1 : t2.free(t1);
  }
  public static TypeStr con(String con) { return make(NOT_NIL,1,con); }

  static public final TypeStr STR0 = make(AND_NIL,-1,"str"); // yes null
  static public final TypeStr STR  = make(NOT_NIL,-1,"str"); // no  null
  static public final TypeStr STR_ = make( OR_NIL, 0,"str"); // choice string, choice nil
  static final TypeStr[] TYPES = new TypeStr[]{STR,STR0};
  static void init1( HashMap<String,Type> types ) {
    types.put("str",STR);
  }
  // Return a String from a TypeStr constant; assert otherwise.
  @Override public String getstr() { assert is_con(); return _con; }

  @Override protected TypeStr xdual() {
    switch(_nil) {
    case  IS_NIL:
      return this;
    case NOT_NIL:
      if( _x==1 ) return this;
      // fallthru
    case AND_NIL:
    case OR_NIL:
      return new TypeStr(xdualnil(),~_x,"str");
    }
    throw typerr(this);
  }
  @Override protected Type xmeet( Type t ) {
    if( t == this ) return this;
    switch( t._type ) {
    case TSTR:   break;
    case TOOP: {
      TypeOop to = (TypeOop)t;
      byte nil = nmeet(to);
      return to._any ? make(nil,_x,_con) : TypeOop.make(nil);
    }
    case TSTRUCT:
    case TTUPLE:
    case TFLT:
    case TINT:
      //if(   may_be_null() ) return t.meet(TypeInt.NULL);
      //if( t.may_be_null() ) return   make_null();
      //return t._type==TFLT||t._type==TINT ? SCALAR : OOP0;
      throw AA.unimpl();
      
    case TRPC:
    case TFUN:   return SCALAR;
    case TERROR: return ((TypeErr)t)._all ? t : this;
    case TNAME:  
    case TUNION: return t.xmeet(this); // Let other side decide
    default: throw typerr(t);
    }
    TypeStr ts = (TypeStr)t;
    //if( _con == ts._con ) return this;
    throw AA.unimpl();
  }

  // Lattice of conversions:
  // -1 unknown; top; might fail, might be free (Scalar->Str); Scalar might lift
  //    to e.g. Float and require a user-provided rounding conversion from F64->Str.
  //  0 requires no/free conversion (Str->Str)
  // +1 requires a bit-changing conversion; no auto-unboxing
  // 99 Bottom; No free converts; e.g. Flt->Str requires explicit rounding
  @Override public byte isBitShape(Type t) {
    throw AA.unimpl();
  }
  @Override public Type widen() {
    throw AA.unimpl();
  }
}
