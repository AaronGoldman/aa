package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Type;
import com.cliffc.aa.TypeErr;

// Merge results
public class RegionNode extends Node {
  int _cidx; // Copy index; monotonic change from zero to Control input this Region is collapsing to
  public RegionNode( Node... ctrls) { super(OP_REGION,ctrls); }
  RegionNode( byte op, Node sc ) { super(op,null,sc); } // For FunNodes
  @Override String str() { return "Region"; }
  @Override public Node ideal(GVNGCM gvn) { return ideal(gvn,false); }
  // Ideal call, but FunNodes keep index#1 for future parsed call sites
  Node ideal(GVNGCM gvn, boolean more) {
    // TODO: The unzip xform, especially for funnodes doing type-specialization
    // TODO: Check for dead-diamond merges
    // TODO: treat _cidx like U/F and skip_dead it also
    if( more ) return null;      // TODO: Check for 2+but_less_than_all alive, and do a partial collapse
    if( _cidx !=0 ) return null; // Already found single control path
    // If only 1 input path is alive, we become a copy on that path
    int idx=0;
    for( int i=1; i<_defs._len; i++ )
      if( gvn.type(at(i))!=TypeErr.ANY ) // Found a live path
        if( idx == 0 ) idx = i;          // Remember live path
        else return null;                // Some other input is alive also
    if( idx==0 ) return null;            // All dead?  Let value() handle it
    // Note: we do not return the 1 alive control path, as then trailing
    // PhiNodes will subsume that control - instead they each need to collapse
    // to their one alive input in their own ideal() calls - and they will be
    // using the _cidx to make their decisions.
    _cidx=idx;                  // Flag the 1 live path
    return this;                // Return self
  }
  @Override public Node is_copy(GVNGCM gvn, int idx) { assert idx==-1; return _cidx == 0 ? null : at(_cidx); }

  @Override public Type value(GVNGCM gvn) {
    Type t = TypeErr.ANY;
    for( int i=1; i<_defs._len; i++ )
      t = t.meet(gvn.type(_defs._es[i]));
    return t;
  }
  @Override public Type all_type() { return Type.CONTROL; }
}
