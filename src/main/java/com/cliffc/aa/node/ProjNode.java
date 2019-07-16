package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeTuple;

// Proj data
public class ProjNode extends Node {
  final int _idx;
  public ProjNode( Node ifn, int idx ) { super(OP_PROJ,ifn); _idx=idx; }
  @Override String xstr() { return "DProj_"+_idx; }
  @Override public Node ideal(GVNGCM gvn) { return in(0).is_copy(gvn,_idx); }
  @Override public Type value(GVNGCM gvn) {
    Type c = gvn.type(in(0));
    if( c instanceof TypeTuple ) {
      TypeTuple ct = (TypeTuple)c;
      if( _idx < ct._ts.length ) {
        Type t = ct._ts[_idx].meet(Type.XSCALAR);
        return Type.SCALAR.isa(t) ? Type.SCALAR : t;
      }
    }
    return c.above_center() ? Type.XSCALAR : Type.SCALAR;
  }
  @Override public Type all_type() { return Type.SCALAR; }

  @Override public int hashCode() { return super.hashCode()+_idx; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof ProjNode) ) return false;
    ProjNode proj = (ProjNode)o;
    return _idx==proj._idx;
  }
}
