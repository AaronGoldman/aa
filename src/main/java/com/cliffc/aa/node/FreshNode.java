package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.tvar.TV3;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeMemPtr;

import static com.cliffc.aa.AA.unimpl;

// "fresh" the incoming TVar: make a fresh instance before unifying
public class FreshNode extends Node {
  public FreshNode( FunNode fun, Node ld ) { super(OP_FRESH, fun, ld); }

  // The lexical scope, or null for top-level
  FunNode fun() {
    return in(0)==Env.CTL_0 ? null : (FunNode)in(0);
  }
  public Node id() { return in(1); } // The HM identifier
  public static Node peek(Node f) { return f instanceof FreshNode fsh ? fsh.id() : f; }
  TV3[] nongen() { return fun()==null ? null : fun()._nongen; }

  @Override public Type value() { return id()._val; }

  @Override public Type live_use(Node def ) {
    if( def==id() ) return _live; // Pass full liveness along
    return Type.ALL;              // Basic aliveness for control
  }

  // Things that can never have type-variable internal structure.
  private static boolean no_tvar_structure(Type t) {
    return t instanceof TypeMemPtr tmp && tmp.is_valtype();
  }

  @Override public boolean has_tvar() { return true; }
  
  @Override public TV3 _set_tvar() {
    //_tvar = new_tvar();
    //if( id()._tvar==null ) id().walk_initype();
    //id().tvar().push_dep(this);
    throw unimpl();
  }

  @Override public boolean unify( boolean test ) {
    TV3[] nongen = nongen();
    return id().tvar().fresh_unify(tvar(),nongen,test);
  }
  // Two FreshNodes are only equal, if they have compatible TVars
  @Override public boolean equals(Object o) {
    if( !has_tvar() ) return this==o;
    if( !(o instanceof FreshNode frsh) ) return false;
    return tvar()==frsh.tvar();
  }
}
