package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeFunPtr;
import com.cliffc.aa.type.TypeMemPtr;

// Extract a Display pointer (a TypeMemPtr) from a TypeFunPtr.
public final class FP2ClosureNode extends Node {
  public FP2ClosureNode( Node funptr ) {
    super(OP_FP2CLO,funptr);
  }
  @Override public Node ideal(GVNGCM gvn, int level) {
    // If at a FunPtrNode, it is only making a TFP out of a code pointer and a
    // display.  Become the display (dropping the code pointer).
    if( in(0) instanceof FunPtrNode )
      return in(0).in(1);
    return null;
  }
  @Override public Type value(GVNGCM gvn) {
    Type tdisp = convert(gvn.type(in(0)),gvn);
    assert tdisp.is_display_ptr();
    assert tdisp != TypeMemPtr.DISPLAY_PTR; // Always better than the default, since comes from a real closure
    return tdisp;
  }
  // This is too high, to support TypeFunPtr closure 'startype' being '~Scalar'
  // instead of TypeMemPtr.DISPLAY_PTR.dual().  OTHERWISE I need to build a
  // recursive version of 'startype' 
  
  //@Override public Type all_type() { return Type.SCALAR; }
  @Override public TypeMemPtr all_type() { return TypeMemPtr.DISPLAY_PTR; }
  static public Type convert( Type t, GVNGCM gvn ) {
    if( !(t instanceof TypeFunPtr) )
      return t.above_center() ? TypeMemPtr.DISPLAY_PTR.dual() : TypeMemPtr.DISPLAY_PTR;
    TypeFunPtr tfp = (TypeFunPtr)t;
    return tfp.display();
  }
}
