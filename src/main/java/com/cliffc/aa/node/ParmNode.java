package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.Type;


// Function parameter node; almost just a Phi with an order.  There is a dense
// numbering matching function arguments, e.g. MEM_IDX for memory.
public class ParmNode extends PhiNode {
  public final int _idx; // Parameter index, MEM_IDX, DSP_IDX is display, ARG_IDX+ normal args
  public ParmNode( int idx, FunNode fun, Parse badgc, Type t, Node defalt ) {
    this(idx,fun,badgc,t);
    add_def(defalt);
  }

  public ParmNode( int idx, FunNode fun, Parse badgc, Type t ) {
    super(OP_PARM,t,badgc,fun);
    assert idx>=0;
    _idx=idx;
  }
  public FunNode fun() { return (FunNode) in(0); }
  @Override public String xstr() { return "Parm:"+_idx; }

  @Override public Type value() {
    // Not executing?
    if( len()==0 ) return Type.ALL;
    Type ctl = val(0);
    if( ctl != Type.CTRL && ctl != Type.ALL ) return ctl.oob();
    Node in0 = in(0);
    if( !(in0 instanceof FunNode fun) )  return ctl.oob();
    if( fun.len()!=len() ) return Type.ALL; // Collapsing

    // Merge all live paths
    Type t = Type.ANY;
    for( int i=1; i<_defs._len; i++ ) {
      Node cprj = fun.in(i);        // Control
      cprj.deps_add(this);          // Changes to control (either up or down) always revisit Parm
      if( cprj._val==Type.CTRL || cprj._val==Type.ALL ) {  // Only meet alive paths
        Type ti = val(i);
        if( cprj instanceof CRProjNode ) {
          // During/after Combo, use the HM type for the GCP type instead of the given default
          if( ti != Type.ANY && _tvar!=null ) {
            Env.ROOT.deps_add(this); // Depends on Root
            ti = tvar().as_flow(this);
            // TODO: Very expensive and rarely needed
            //_tvar.deps_add_deep(this); // Updates to tvar recompute flow
          }
        }
        t = t.meet(ti);
      }
    }
    return t.join(_t);
  }

  @Override public Node ideal_reduce() {
    if( !(in(0) instanceof FunNode) )
      return in(0).is_copy(_idx); // Dying, or thunks
    FunNode fun = fun();
    if( fun._val == Type.XCTRL ) return null; // All dead, c-prop will fold up
    if( fun._defs._len!=_defs._len ) return null;
    if( fun.is_copy(0)!=null )       // FunNode is a Copy
      return in(1)==this ? Env.ANY : in(1);             // So return the copy
    // Do not otherwise fold away, as this lets Nodes in *this* function depend
    // on values in some other function... which, if code-split, gets confused
    // (would have to re-insert the Parm).
    return null;
  }

  // While Parms are mostly Phis (and yes for value flows), during unification
  // Parms are already treated by the H-M algo, and (via fresh_unify) get
  // "fresh" TVars for every input path.
  @Override public boolean unify( boolean test ) { return false; }
  
  @Override public int hashCode() { return super.hashCode()+_idx; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof ParmNode parm) ) return false;
    return _idx==parm._idx;
  }
}
