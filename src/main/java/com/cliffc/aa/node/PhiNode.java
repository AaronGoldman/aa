package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;

// Merge results; extended by ParmNode
public class PhiNode extends Node {
  final Parse _badgc;
  final BitsAlias _bits;
  private PhiNode( byte op, Parse badgc, Node... vals ) {
    super(op,vals);
    _badgc = badgc;
    _bits = BitsAlias.NZERO;
  }
  public PhiNode( Parse badgc, Node... vals ) { this(OP_PHI,badgc,vals); }
  // For ParmNodes
  PhiNode( byte op, Node fun, ConNode defalt, Parse badgc ) { this(op,badgc,fun,defalt); }
  
  @Override public Node ideal(GVNGCM gvn) {
    if( gvn.type(in(0)) == Type.XCTRL ) return null;
    RegionNode r = (RegionNode) in(0);
    assert r._defs._len==_defs._len;
    if( gvn.type(r) == Type.XCTRL ) return null; // All dead, c-prop will fold up
    if( r._defs.len() > 1 &&  r.in(1) == Env.ALL_CTRL ) return null;
    // If only 1 unique live input, return that
    Node live=null;
    for( int i=1; i<_defs._len; i++ ) {
      if( gvn.type(r.in(i))==Type.XCTRL ) continue; // Ignore dead path
      if( in(i)==this || in(i)==live ) continue;    // Ignore self or duplicates
      if( live==null ) live = in(i);                // Found unique live input
      else return null;         // Found 2nd live input, no collapse
    }
    return live;                // Single unique input
  }
  @Override public Type value(GVNGCM gvn) {
    Type ctl = gvn.type(in(0));
    if( ctl != Type.CTRL ) return ctl.above_center() ? Type.ANY : Type.ALL;
    RegionNode r = (RegionNode) in(0);
    assert r._defs._len==_defs._len;
    Type t = Type.ANY;
    for( int i=1; i<_defs._len; i++ )
      if( gvn.type(r.in(i))==Type.CTRL ) // Only meet alive paths
        t = t.meet(gvn.type(in(i)));
    return t;                   // Limit to sane results
  }
  @Override public Type all_type() { return Type.ALL; }
  @Override public String err(GVNGCM gvn) {
    if( !(in(0) instanceof FunNode && ((FunNode)in(0))._name.equals("!") ) && // Specifically "!" takes a Scalar
        (gvn.type(this).contains(Type.SCALAR) ||
         gvn.type(this).contains(Type.NSCALR)) ) // Cannot have code that deals with unknown-GC-state
      return _badgc.errMsg("Cannot mix GC and non-GC types");
    return null;
  }

  // Split this node into a set returning 'bits' and the original which now
  // excludes 'bits'.  Return null if already making a subset of 'bits'.
  Node split_memory_use( GVNGCM gvn, BitsAlias bits ) {
    Type t = gvn.type(this);
    if( !(t instanceof TypeMem) ) return null;
    TypeMem tm = (TypeMem)t;
    assert tm==TypeMem.XMEM || tm.contains(bits);
    if( gvn.type(in(0)) == Type.XCTRL ) return null; // Will fold up elsewhere
    if( _defs._len<=2 ) return null;                 // Will fold up elsewhere

    // See if already split proper
    if( bits.isa(_bits) ) return null;

    // Split a Phi - enlarges the graph but sharpens the aliasing.  Look for an
    // already-split Phi with the proper aliasing (and also assert no duplicate
    // aliasing).
    
    
    
    throw AA.unimpl();
  }
  
}
