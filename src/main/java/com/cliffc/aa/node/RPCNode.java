package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;

// Jump-to a specific RPC
public class RPCNode extends Node {
  final int _rpc;
  RPCNode( Node ctrl, Node epi, int rpc ) { super(OP_RPC,ctrl,epi); _rpc=rpc; }
  String xstr() { return "RPC#"+_rpc; } // Self short name
  String  str() { return xstr(); }      // Inline short name
  @Override public Node ideal(GVNGCM gvn) { return at(0).is_copy(gvn,0); }
  @Override public Type value(GVNGCM gvn) {
    Node  ctrl = at(0),  rpc = at(1);
    Type tctrl,         trpc;
    if( rpc == ctrl ) { // Pointing at a function epilog?
      assert ctrl instanceof EpilogNode;
      TypeTuple tepi = (TypeTuple)gvn.type(ctrl);
      assert tepi.is_fun_ptr();
      tctrl = tepi.at(0); // Get types from the epilog directly
      trpc  = tepi.at(2);
    } else { // Else bypassing the epilog and inlining
      tctrl = gvn.type(ctrl);  // Types from the function body
      trpc =  gvn.type(rpc );
    }
    if( tctrl instanceof TypeErr ) return tctrl;
    if( trpc  instanceof TypeErr ) return trpc ;
    if( tctrl == Type.XCTRL ) return Type.XCTRL;
    return ((TypeRPC)trpc).test(_rpc) ? Type.CTRL : Type.XCTRL;
  }
  @Override public Type all_type() { return Type.CTRL; }
  @Override public int hashCode() { return super.hashCode()+_rpc; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof RPCNode) ) return false;
    RPCNode rpc = (RPCNode)o;
    return _rpc==rpc._rpc;
  }
}
