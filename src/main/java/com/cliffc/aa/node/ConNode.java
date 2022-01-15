package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.type.*;

import java.util.function.Predicate;

import static com.cliffc.aa.AA.unimpl;

// Constant value nodes; no computation needed.  Hashconsed for unique
// constants, except for XNIL.  XNIL allows for a TV2 typevar Nilable-Leaf with
// each Leaf unifying on its own.
public class ConNode<T extends Type> extends Node {
  T _t;                         // Not final for testing
  public ConNode( T t ) {
    super(OP_CON,Env.START);
    _t=t;
    _live = all_live();
  }
  @Override public String xstr() {
    if( Env.ALL_PARM == this ) return "ALL_PARM";
    if( Env.ALL_CALL == this ) return "ALL_CALL";
    if( Env.DEF_MEM  == this ) return "DEF_MEM";
    return _t==null ? "(null)" : _t.toString();
  }
  @SuppressWarnings("unchecked")
  @Override void walk_reset0() {
    if( this==Env.DEF_MEM )
      _t=(T)(_val=_live=TypeMem.ALLMEM);
  }

  @SuppressWarnings("unchecked")
  public void set_alias(int alias,TypeStruct ts) {
    unelock();                  // Changing hash
    _t = (T)((TypeMem)_t).make_from(alias,ts);
    Env.GVN.add_flow(this);
  }

  @Override public Type value() { return _t.simple_ptr(); }
  @Override public TypeMem all_live() { return _t instanceof TypeMem ? TypeMem.ALLMEM : TypeMem.ALIVE; }

  @Override public TV2 new_tvar(String alloc_site) {
    if( _t==Type.CTRL || _t==Type.XCTRL || _t instanceof TypeRPC || _t instanceof TypeMem )
      return null;
    if( this == Env.XUSE || this == Env.ALL || this==Env.ALL_PARM ) return null;
    if( _t == Type.XNIL || _t == Type.NIL )
      return TV2.make_nil(TV2.make_leaf(this,alloc_site),alloc_site);
    if( _t == Type.ANY ) return TV2.make_leaf(this,alloc_site);
    if( _t instanceof TypeInt ) {
      NewNode nnn = Env.SCP_0.get_type("int:");
      return nnn._tvar==null ? (nnn._tvar = TV2.make_leaf(nnn,alloc_site)) : nnn._tvar;
    }
    if( _t instanceof TypeFlt ) {
      NewNode nnn = Env.SCP_0.get_type("flt:");
      return nnn._tvar==null ? (nnn._tvar = TV2.make_leaf(nnn,alloc_site)) : nnn._tvar;
    }
    throw unimpl();
  }

  @Override public boolean unify( boolean test ) { return false; }

  @Override public String toString() { return str(); }
  @Override public int hashCode() {
    // In theory also slot 0, but slot 0 is always Start.
    // Two XNILs are typically different because their TV2s are different
    return _t.hashCode();
  }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !(o instanceof ConNode con) ) return false;
    if( this==Env.DEF_MEM || o==Env.DEF_MEM ) return false;
    if( _t==Type.XNIL && con._t==Type.XNIL /*&& tvar()!=con.tvar()*/ )
      return false;             // Different versions of TV2 NotNil
    return _t==con._t;
  }
  @Override Node walk_dom_last( Predicate<Node> P) { return null; }
  @SuppressWarnings({"unused","unchecked"}) // Found as java_class_node in _prims.aa
  public static class PI extends ConNode {
    public PI() { super(TypeFlt.PI); }
    @Override public Node clazz_node( ) { return Env.GVN.init(this); }
  }
}

