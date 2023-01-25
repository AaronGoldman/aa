package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.tvar.TV3;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeFlt;
import com.cliffc.aa.type.TypeNil;

import java.util.function.Predicate;

// Constant value nodes; no computation needed.  Hashconsed for unique
// constants, except for XNIL.  XNIL allows for a TV3 typevar Nilable-Leaf with
// each Leaf unifying on its own.
public class ConNode<T extends Type> extends Node {
  T _t;                         // Not final for testing
  public ConNode( T t ) {
    super(OP_CON,Env.ROOT);
    assert t.simple_ptr()==t;
    _t=t;
  }
  @Override public String xstr() {
    return _t==null ? "(null)" : _t.toString();
  }

  @Override public Type value() { return _t; }

  @Override public boolean has_tvar() {
    if( _t==Type.ALL || _t==Type.ANY ) return true;  // Specifically allowed for various unused-displays on primitives
    if( _t instanceof TypeNil ) return true; // Yes on NIL, INT, FLT, MEMPTR, FUNPTR, STRUCT
    // No for TFLD, TMEM, RPC
    return false;
  }

  @Override public TV3 _set_tvar() {
    unelock(); // Hash now depends on TVars
    return TV3.from_flow(_t);
  }
  
  @Override public boolean unify( boolean test ) { return false; }

  @Override public String toString() { return str(); }
  @Override public int hashCode() {
    // In theory also slot 0, but slot 0 is always Start.
    // Two XNILs are typically different because their TV3s are different
    return _t.hashCode() + (_tvar == null ? 0 : _tvar._uid);
  }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !(o instanceof ConNode con) ) return false;
    if( _t!=con._t ) return false;
    // Prior to Combo we must assume two XNILs will unify to different TV3
    // types and thus must remain seperate.  After Combo they can fold together
    // if they have the same TVars.
    if( _t==TypeNil.XNIL && _tvar==null ) return false;
    
    // Check TVars, if they exist.  This allows combining ConNodes with TVars
    // pre-Combo, except for XNIL.  E.g. all ints (or floats) are alike to TV3.
    if( _tvar==null ) { assert con._tvar==null; return true; }
    return tvar()==con.tvar();
  }
  
  @Override Node walk_dom_last( Predicate<Node> P) { return null; }
  @SuppressWarnings({"unused","unchecked"}) // Found as java_class_node in _prims.aa
  public static class PI extends ConNode {
    public PI() { super(TypeFlt.PI); }
    @Override public Node clazz_node( ) { return Env.GVN.init(this); }
  }
}

