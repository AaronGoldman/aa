package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeTuple;

// Proj control
public class CProjNode extends ProjNode {
  public CProjNode( Node ifn, int idx ) { super(ifn,idx); }
  @Override String xstr() {
    if( in(0) instanceof IfNode )
      return _idx==0 ? "False" : "True";
    return "CProj_"+_idx;
  }
  @Override public Node ideal(GVNGCM gvn) { return in(0).is_copy(gvn,_idx); }
  @Override public Type value(GVNGCM gvn) {
    Type c = gvn.type(in(0));  // our type is just the matching tuple slice
    if( c.isa(TypeTuple.IF_ANY) ) return Type.XCTRL;
    if( TypeTuple.IF_ALL.isa(c) ) return Type. CTRL;
    if( !(c instanceof TypeTuple) ) return Type.CTRL;
    TypeTuple ct = (TypeTuple)c;
    Type res = ct.at(_idx);
    return res==Type.XCTRL ? Type.XCTRL : Type.CTRL;
  }
  @Override public Type all_type() { return Type.CTRL; }
  // Return the op_prec of the returned value.  Not sensible except
  // when call on primitives.
  @Override public byte op_prec() { return _defs.at(0).op_prec(); }

  // Used in Parser just after an if-test to sharpen the tested variables.
  // This is a mild optimization, since e.g. follow-on Loads which require a
  // non-nil check will hash to the pre-test Load, and so bypass this
  // sharpening.
  @Override public Node sharpen( GVNGCM gvn, Node mem ) {
    Node iff = in(0);
    if( !(iff instanceof IfNode) ) return this; // Already collapsed IfNode, no sharpen
    Node test = iff.in(1);
    Node sharp = _idx==1
      ? gvn.xform(new CastNode(this,test,Type.NSCALR))
      : gvn.con(Type.NIL);
    // If 'test' has an appearance under a name, then store the sharp value to
    // that name.
    Node x = mem;
    while( x instanceof StoreNode ) {
      StoreNode st = (StoreNode)x;
      if( st.val()==test )
        mem = gvn.xform(new StoreNode(st,this,mem,st.adr(),sharp,true));
      x = st.mem();
    }
    gvn.add_work(sharp);
    return mem;
  }
}
