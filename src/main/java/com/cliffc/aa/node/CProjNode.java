package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.Env;
import com.cliffc.aa.type.Type;

// Proj control
public class CProjNode extends ProjNode {
  public CProjNode( Node ifn ) { this(ifn,AA.CTL_IDX); }
  public CProjNode( Node ifn, int idx ) { super(OP_CPROJ,ifn,idx); }
  @Override public String xstr() {
    if( !is_dead() && in(0) instanceof IfNode )
      return _idx==0 ? "False" : "True";
    return "CProj"+_idx;
  }
  @Override public Type value() {
    if( in(0)._op==OP_ROOT ) return Type.CTRL; // Program Start
    // Normal projection, except pinch to CTRL.
    Type x = super.value();
    if( x==Type.ANY ) return Type.XCTRL;
    if( x==Type.ALL ) return Type. CTRL;
    return x;
  }
  @Override public void add_flow_use_extra(Node chg) {
    // Control from Calls
    if( chg instanceof CallNode ) {
      // if the Call changes val the function might be callable
      for( Node fun : _uses )
        if( fun instanceof FunNode )
          Env.GVN.add_flow(fun);
    }
  }
}
