package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

// Load a named field from a struct.  Does it's own null-check testing.  Loaded
// value depends on the struct typing.
public class LoadNode extends Node {
  private final String _fld;
  private final String _badfld;
  private final String _badnil;
  public LoadNode( Node ctrl, Node st, String fld, Parse bad ) {
    super(OP_LOAD,ctrl,st);
    _fld = fld;
    _badfld = bad.errMsg("Unknown field '."+fld+"'");
    _badnil = bad.errMsg("Struct might be nil when reading field '."+fld+"'");
  }
  String xstr() { return "."+_fld; }    // Self short name
  String  str() { return xstr(); }      // Inline short name
  @Override public Node ideal(GVNGCM gvn) {
    Node ctrl = in(0);
    Node addr = in(1);
    if( ctrl==null || gvn.type(ctrl)!=Type.CTRL )
      return null; // Dead load, or a no-control-no-fail load
    Type t = gvn.type(addr);    // Address type
    if( t instanceof TypeErr ) return null;

    // Lift control on Loads as high as possible... and move them over
    // to a CastNode (to remove null-ness) and remove the control.
    if( !t.may_have_nil() )     // No null, no need for ctrl
      // remove ctrl; address already casts-away-null
      return set_def(0,null,gvn);

    // Looking for a null-check pattern:
    //   this.0->dom*->True->If->addr
    //   this.1->[Cast]*-------/   Cast(s) are optional
    // If found, convert to this pattern:
    //   this.0      ->True->If->addr
    //   this.1->Cast/---------/
    // Where the cast-away-null is local and explicit
    Node baseaddr = addr;
    while( baseaddr instanceof CastNode ) baseaddr = baseaddr.in(1);
    final Node fbaseaddr = baseaddr;

    Node tru = ctrl.walk_dom_last(n ->
                                  n instanceof CProjNode && ((CProjNode)n)._idx==1 &&
                                  n.in(0) instanceof IfNode &&
                                  n.in(0).in(1) == fbaseaddr );
    if( tru==null ) return null;
    assert !(tru==ctrl && addr != baseaddr) : "not the immediate location or we would be not-null already";
    set_def(1,gvn.xform(new CastNode(tru,baseaddr,((TypeStruct)t).make_nil(TypeStruct.NOT_NIL))),gvn);
    return set_def(0,null,gvn);
  }
  @Override public Type value_ne(GVNGCM gvn) {
    Type t = gvn.type_ne(in(1));
    while( t instanceof TypeName ) t = ((TypeName)t)._t;
    if( t.isa(TypeOop.OOP_) ) return Type.XSCALAR; // Very high address; might fall to any valid value
    Type tnil = t.may_have_nil() ? TypeErr.make(_badnil) : Type.ANY; // Null compile-time error
    if( TypeOop.OOP0.isa(t) )            // Too low, might not have any fields
      return tnil.meet(TypeErr.make(_badfld));
    if( t instanceof TypeStruct ) {
      TypeStruct ts = (TypeStruct)t;
      int idx = ts.find(_fld);  // Find the named field
      Type tfld = idx == -1 ? TypeErr.make(_badfld) : ts.at(idx); // Field type
      return tnil.meet(tfld);
    }
    if( tnil != Type.ANY ) return tnil;
    
    throw AA.unimpl();
  }
  @Override public int hashCode() { return super.hashCode()+_fld.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof LoadNode) ) return false;
    LoadNode ld = (LoadNode)o;
    return _fld.equals(ld._fld);
  }
}
