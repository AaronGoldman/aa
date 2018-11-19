package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeStruct;

import java.util.Arrays;

// Make a new object of given type
public class NewNode extends Node {
  private final String[] _names; // Field names
  public NewNode( String[] names, Node[] flds ) {
    super(OP_NEW,flds);
    assert flds [0]==null;      // no ctrl field
    assert names.length==flds.length-1;
    _names=names;
  }
  String xstr() { return "New#"; } // Self short name
  String  str() { return xstr(); } // Inline short name
  @Override public Node ideal(GVNGCM gvn) { return null; }
  @Override public Type value(GVNGCM gvn) {
    Type[] ts = new Type[_defs._len-1];
    for( int i=0; i<ts.length; i++ ) {
      ts[i] = gvn.type(in(i+1));
      // Limit to Scalar results
      if( ts[i]==Type.ANY ) ts[i] = Type.XSCALAR;
      if( ts[i]==Type.ALL ) ts[i] = Type. SCALAR;
    }
    Type newt = TypeStruct.make(_names,ts);
    // Get the existing type, without installing if missing because blows the
    // "new newnode" assert if this node gets replaced during parsing.
    Type oldt = gvn.self_type(this);
    Type rez = newt.contains(oldt) ? newt.meet(oldt) : newt;
    return rez;
  }
  // Worse-case type for this Node
  @Override public Type all_type() {
    Type[] ts = new Type[_names.length];
    Arrays.fill(ts,Type.SCALAR);
    return TypeStruct.make(_names,ts);
  }
  
  @Override public int hashCode() { return super.hashCode()+ Arrays.hashCode(_names); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof NewNode) ) return false;
    NewNode nnn = (NewNode)o;
    return Arrays.equals(_names,nnn._names);
  }
}
