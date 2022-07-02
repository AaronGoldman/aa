package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.type.*;

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
  TV2[] nongen() { return fun()==null ? null : fun()._nongen; }

  @Override public Type value() { return id()._val; }
  @Override public void add_flow_extra(Type old) {
    // Types changed, now might collapse
    if( !no_tvar_structure(old) && no_tvar_structure(_val) )
      Env.GVN.add_reduce(this);
  }

  @Override public Type live_use(Node def ) {
    if( def==id() ) return _live; // Pass full liveness along
    return Type.ALL;              // Basic aliveness for control
  }

  // Things that can never have type-variable internal structure.
  private static boolean no_tvar_structure(Type t) {
    return t instanceof TypeMemPtr tmp && tmp.is_valtype();
  }

  @Override public boolean has_tvar() { return true; }
  
  @Override public void set_tvar() {
    _tvar = new_tvar();
    if( id()._tvar==null ) id().walk_initype();
    id().tvar().push_dep(this);
  }

  @Override public boolean unify( boolean test ) {
    TV2[] nongen = nongen();
    return id().tvar().fresh_unify(tvar(),nongen,test);
  }
  @Override public void add_work_hm() {
    super.add_work_hm();
    Env.GVN.add_flow(id());
    TV2 t = id().tvar();
    if( t.nongen_in(nongen()) )
      t.add_deps_flow(); // recursive work.add(_deps)
  }

  @Override public Node ideal_reduce() {
    if( id()==this ) return null; // Dead self-cycle
    // Remove Fresh of base type values: things that can never have structure.
    if( no_tvar_structure(_val) )
      return id();
    // Remove if TVar has already unified with the input.
    if( _tvar!=null && tvar()==id().tvar() )
     return id();

    return null;
  }

  // Two FreshNodes are only equal, if they have compatible TVars
  @Override public boolean equals(Object o) {
    if( _tvar==null ) return this==o;
    if( !(o instanceof FreshNode) ) return false;
    //return tvar().compatible(((FreshNode) o).tvar());
    throw unimpl();
  }
}
