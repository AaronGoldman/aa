package com.cliffc.aa.type;

import com.cliffc.aa.util.SB;

import java.util.BitSet;
import java.util.HashMap;
import java.util.function.Predicate;

// Pointers-to-memory; these can be both the address and the value part of
// Loads and Stores.  They carry a set of aliased TypeObjs.
public final class TypeMemPtr extends Type<TypeMemPtr> {
  // List of known memory aliases.  Zero is nil.
  BitsAlias _aliases;
  public TypeObj _obj;          // Meet/join of aliases

  private TypeMemPtr(BitsAlias aliases, TypeObj obj ) { super     (TMEMPTR); init(aliases,obj); }
  private void init (BitsAlias aliases, TypeObj obj ) { super.init(TMEMPTR); _aliases = aliases; _obj=obj; }
  @Override int compute_hash() { return TMEMPTR + _aliases._hash + _obj._hash; }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeMemPtr) ) return false;
    TypeMemPtr tf = (TypeMemPtr)o;
    return _aliases==tf._aliases && _obj==tf._obj;
  }
  @Override public boolean cycle_equals( Type o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeMemPtr) ) return false;
    TypeMemPtr t2 = (TypeMemPtr)o;
    if( _aliases != t2._aliases ) return false;
    return _obj == t2._obj || _obj.cycle_equals(t2._obj);
  }
  @Override String str( BitSet dups) {
    if( dups == null ) dups = new BitSet();
    else if( dups.get(_uid) ) return "*"; // Break recursive printing cycle
    dups.set(_uid);
    SB sb = new SB().p('*');
    _aliases.toString(sb).p(_obj.str(dups));
    if( _aliases.test(0) ) sb.p('?');
    return sb.toString();
  }

  private static TypeMemPtr FREE=null;
  @Override protected TypeMemPtr free( TypeMemPtr ret ) { FREE=this; return ret; }
  public static TypeMemPtr make(BitsAlias aliases, TypeObj obj ) {
    if( aliases==BitsAlias.NIL ) obj=obj.above_center() ? TypeObj.XOBJ : TypeObj.OBJ;
    TypeMemPtr t1 = FREE;
    if( t1 == null ) t1 = new TypeMemPtr(aliases,obj);
    else { FREE = null;          t1.init(aliases,obj); }
    TypeMemPtr t2 = (TypeMemPtr)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }
  public static TypeMemPtr make    ( int alias, TypeObj obj ) { return make(BitsAlias.make0(alias)           ,obj); }
         static TypeMemPtr make_nil( int alias, TypeObj obj ) { return make(BitsAlias.make0(alias).meet_nil(),obj); }
  public        TypeMemPtr make    (            TypeObj obj ) { return make(_aliases,obj); }

  public static final TypeMemPtr OOP0   = make(BitsAlias.FULL    , TypeObj.OBJ); // Includes nil
  public static final TypeMemPtr OOP    = make(BitsAlias.NZERO   , TypeObj.OBJ);// Excludes nil
  public static final TypeMemPtr STRPTR = make(BitsAlias.STRBITS , TypeStr.STR);
         static final TypeMemPtr STR0   = make(BitsAlias.STRBITS0, TypeStr.STR);
  public static final TypeMemPtr ABCPTR = make(BitsAlias.ABCBITS , TypeStr.ABC);
  public static final TypeMemPtr ABC0   = make(BitsAlias.ABCBITS0, TypeStr.ABC);
  public static final TypeMemPtr STRUCT = make(BitsAlias.RECBITS , TypeStruct.ALLSTRUCT);
         static final TypeMemPtr STRUCT0= make(BitsAlias.RECBITS0, TypeStruct.ALLSTRUCT);
  public static final TypeMemPtr PNTPTR = make(BitsAlias.RECBITS , TypeName.TEST_STRUCT);
         static final TypeMemPtr PNT0   = make(BitsAlias.RECBITS0, TypeName.TEST_STRUCT);
  public static final TypeMemPtr NIL    = make(BitsAlias.NIL,      TypeObj.OBJ);
  static final TypeMemPtr[] TYPES = new TypeMemPtr[]{OOP0,STRPTR,ABCPTR,STRUCT,ABC0,PNTPTR,PNT0};

  @Override protected TypeMemPtr xdual() { return new TypeMemPtr(_aliases.dual(),(TypeObj)_obj.dual()); }
  @Override TypeMemPtr rdual() {
    if( _dual != null ) return _dual;
    TypeMemPtr dual = _dual = new TypeMemPtr(_aliases,(TypeObj)_obj.rdual());
    dual._dual = this;
    dual._cyclic = true;
    return dual;
  }
  @Override protected Type xmeet( Type t ) {
    switch( t._type ) {
    case TMEMPTR:break;
    case TFLT:
    case TINT:
    case TFUNPTR:
    case TRPC:   return cross_nil(t);
    case TNIL:
    case TNAME:  return t.xmeet(this); // Let other side decide
    case TOBJ:
    case TSTR:
    case TSTRUCT:
    case TTUPLE:
    case TFUN:
    case TMEM:   return ALL;
    default: throw typerr(t);   // All else should not happen
    }
    // Meet of aliases
    TypeMemPtr ptr = (TypeMemPtr)t;
    return make(_aliases.meet(ptr._aliases), (TypeObj)_obj.meet(ptr._obj));
  }
  @Override public boolean above_center() { return _aliases.above_center(); }
  // Aliases represent *classes* of pointers and are thus never constants.
  // nil is a constant.
  @Override public boolean may_be_con()   { return may_nil(); }
  @Override public boolean is_con()       { return !_aliases.is_class(); } // only nil
  @Override public boolean must_nil() { return _aliases.test(0) && !above_center(); }
  @Override public boolean may_nil() { return _aliases.may_nil(); }
  @Override Type not_nil() {
    BitsAlias bits = _aliases.not_nil();
    return bits==_aliases ? this : make(bits,_obj);
  }
  @Override public Type meet_nil() {
    if( _aliases.test(0) )      // Already has a nil?
      return _aliases.above_center() ? TypeNil.NIL : this;
    return make(_aliases.meet_nil(),_obj);
  }

  // Build a depth-limited named type
  @Override TypeMemPtr make_recur(TypeName tn, int d, BitSet bs ) {
    Type t2 = _obj.make_recur(tn,d,bs);
    return t2==_obj ? this : make(_aliases,(TypeObj)t2);
  }
  // Mark if part of a cycle
  @Override void mark_cycle( Type head, BitSet visit, BitSet cycle ) {
    if( visit.get(_uid) ) return;
    visit.set(_uid);
    if( this==head ) { cycle.set(_uid); _cyclic=_dual._cyclic=true; }
    _obj.mark_cycle(head,visit,cycle);
    if( cycle.get(_obj._uid) )
      { cycle.set(_uid); _cyclic=_dual._cyclic=true; }
  }
  @Override boolean contains( Type t, BitSet bs ) { return _obj == t || _obj.contains(t, bs); }
  @Override int depth( BitSet bs ) { return 1+_obj.depth(bs); }
  @SuppressWarnings("unchecked")
  @Override Type replace( Type old, Type nnn, HashMap<Type,Type> MEETS  ) {
    Type x = _obj.replace(old,nnn,MEETS);
    if( x==_obj ) return this;
    Type rez = make((TypeObj)x);
    rez._cyclic=true;
    return rez;
  }

  @SuppressWarnings("unchecked")
  @Override void walk( Predicate<Type> p ) { if( p.test(this) ) _obj.walk(p); }
  @Override TypeStruct repeats_in_cycles(TypeStruct head, BitSet bs) { return _cyclic ? _obj.repeats_in_cycles(head,bs) : null; }
  public int getbit() { return _aliases.getbit(); }
}
