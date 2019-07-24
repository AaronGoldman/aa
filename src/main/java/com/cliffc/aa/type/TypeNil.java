package com.cliffc.aa.type;

import java.util.BitSet;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

// Nil types are just a nil, but along a particular type domain.  Used so the
// parser can just parse a '0' as the same nil for all other types.  Nil values
// are represented as all-zero-bits, and are limited to Scalar types like
// pointers (both to functions and memory) and numbers, and specifically
// excludes *memory* things like TypeStruct.  TypePtr-to-TypeMem-of-TypeStruct
// is fine, but not the memory itself.
public class TypeNil extends Type<TypeNil> {
  public  Type _t;
  private TypeNil  ( Type t ) { super(TNIL); init(t); }
  private void init( Type t ) { _t=t; }
  @Override int compute_hash() { return TNIL + (_t==null ? 0 : _t._hash); }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeNil) ) return false;
    TypeNil t2 = (TypeNil)o;
    return _t==t2._t;
  }
  @Override public boolean cycle_equals( Type o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeNil) ) return false;
    TypeNil t2 = (TypeNil)o;
    return _t==t2._t || (_t!=null && t2._t != null && _t.cycle_equals(t2._t));
  }
  @Override String str( BitSet dups) {
    if( _t==null ) return "nil";
    return _t.str(dups)+(_t.above_center() ? "+0" : "?");
  }
  public static void init() {} // Used to force class init
  
  private static TypeNil FREE=null;
  @Override protected TypeNil free( TypeNil ret ) {
    assert intern_lookup()!=this;
    FREE=this; this._dual=null; this._t=Type.ANY;
    return ret;
  }
  private static TypeNil make0( Type t ) {
    assert t==null || (t.isa(SCALAR) &&
                       !t.is_num() && // Numbers fold in zero directly
                       !(t instanceof TypeMemPtr)); // Ptr folds in zero directly
    TypeNil t1 = FREE;
    if( t1 == null ) t1 = new TypeNil(t);
    else { FREE = null; t1.init(t); }
    TypeNil t2 = (TypeNil)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }
  public static Type make( Type t ) {
    if( t==NSCALR ) return SCALAR;
    if( t==XNSCALR ) return XSCALAR;
    return t == SCALAR || t == XSCALAR || t instanceof TypeNil ? t : make0(t);
  }
  
  // This is the Parser's canonical NIL, suitable for initializing all data
  // types.  It is not in the lattice, and is not returned from any meet
  // (except when meet'ing itself).
  public  static final TypeNil NIL = make0(null);
  // NIL is not in the lattice.
  static final TypeNil[] TYPES = new TypeNil[]{};
  
  @Override public long   getl() { assert is_con(); return 0; }
  @Override public double getd() { assert is_con(); return 0; }

  @Override TypeNil xdual() { return _t==null ? this : new TypeNil(_t. dual()); }
  @Override TypeNil rdual() {
    if( _dual != null ) return _dual;
    assert _t!=null; // NIL has no out-edges and cannot be part of a cycle
    TypeNil dual = _dual= new TypeNil(_t.rdual());
    dual._dual = this;
    dual._cyclic = true;
    return dual;
  }
  @Override protected Type xmeet( Type t ) {
    assert t.base()==t || !(t.base() instanceof TypeNil); // No name-wrapping-nils
    if( this == NIL ) return t   .meet_nil();
    if( t    == NIL ) return this.meet_nil();
    if( above_center() )           // choice-nil
      return t instanceof TypeNil  // aways keep nil (choice or not)
        ? make(_t.meet(((TypeNil)t)._t))
        :      _t.meet(t);      // toss away nil choice
    else {                      // must-nil
      // Keep the nil (and remove any double-nil)
      Type tm;
      if( t instanceof TypeNil ) {
        TypeNil tn = (TypeNil)t;
        tm = _t.meet(tn._t);
        if( tm == tn._t ) return tn;
      } else {
        tm = _t.meet(t);
      }
      return tm.isa(SCALAR) ? make(tm) : ALL;
    }
  }

  @Override public boolean above_center() { return _t != null && _t.above_center(); }
  @Override public boolean may_be_con() { return _t==null || _t.may_be_con(); }
  @Override public boolean is_con()   { return _t == null; } // Constant nil
  @Override public byte isBitShape(Type t) { return _t==null || this==t ? 0 : _t.isBitShape(t); }
  @Override public boolean must_nil() { return _t==null || !_t.above_center(); }
  @Override Type not_nil() { return _t==null ? Type.SCALAR :(_t.above_center() ? _t : this); }
  @Override public Type meet_nil() { return _t.above_center() ? NIL : this; }
  // Make a (possibly cyclic & infinite) named type.  Prevent the infinite
  // unrolling of names by not allowing a named-type with depth >= D from
  // holding (recursively) the head of a named-type cycle.  We need to cap the
  // unroll, to prevent loops/recursion from infinitely unrolling.
  @Override Type make_recur(TypeName tn, int d, BitSet bs ) {
    if( _t==null ) return this; // No recursion on NIL
    Type t2 = _t.make_recur(tn,d,bs);
    // Build a depth-limited version of the same TypeNil
    return t2==_t ? this : make(t2);
  }
  // Mark if part of a cycle
  @Override void mark_cycle( Type head, BitSet visit, BitSet cycle ) {
    if( visit.get(_uid) ) return;
    visit.set(_uid);
    if( this==head ) { cycle.set(_uid); _cyclic=_dual._cyclic=true; }
    if( _t != null ) {
      _t.mark_cycle(head,visit,cycle);
      if( cycle.get(_t._uid) )
        { cycle.set(_uid); _cyclic=_dual._cyclic=true; }
    }
  }
  
  // Iterate over any nested child types
  @Override public void iter( Consumer<Type> c ) { c.accept(_t); }
  @Override boolean contains( Type t, BitSet bs ) { return _t == t || (_t != null && _t.contains(t, bs)); }
  @Override int depth( BitSet bs ) { return 1+(_t==null ? 0 : _t.depth(bs)); }
  @SuppressWarnings("unchecked")
  @Override Type replace( Type old, Type nnn, HashMap<Type,Type> HASHCONS ) {
    if( _t==null ) return this;
    Type x = _t.replace(old,nnn,HASHCONS);
    if( x==_t ) return this;
    TypeNil rez = make0(x);
    rez._cyclic=true;
    TypeNil hc = (TypeNil)HASHCONS.get(rez);
    if( hc == null ) { HASHCONS.put(rez,rez); return rez; }
    return rez.free(hc);
  }
  @SuppressWarnings("unchecked")
  @Override void walk( Predicate<Type> p ) { if( p.test(this) && _t!=null ) _t.walk(p); }
  @Override TypeStruct repeats_in_cycles(TypeStruct head, BitSet bs) {
    if( !_cyclic || _t==null ) return null;
    return _t.repeats_in_cycles(head,bs);
  }
}
