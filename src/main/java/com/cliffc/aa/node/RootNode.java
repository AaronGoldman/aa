package com.cliffc.aa.node;

import com.cliffc.aa.*;

// Sea-of-Nodes
public class RootNode extends Node {
  @Override String str() { return "root"; }
  @Override public Node ideal(GVNGCP gvn) { return null; }
  @Override public Type value(GVNGCP gvn) { return Type.ALL; }
}
