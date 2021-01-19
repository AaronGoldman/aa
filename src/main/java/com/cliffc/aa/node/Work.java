package com.cliffc.aa.node;

import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.VBitSet;

import java.util.function.Function;

public abstract class Work implements Function<Node,Node> {
  final Ary<Node> _work = new Ary<>(new Node[1],0);
  final VBitSet _on = new VBitSet();
  final String _name;
  public Work(String name) { _name=name; }
  public <N extends Node> N add(N n) {
    if( !_on.tset(n._uid) ) _work.push(n);
    return n;
  }
  public abstract Node apply(Node n);

  public Node pop() {
    if( _work._len==0 ) return null;
    Node n = _work.pop();
    _on.clear(n._uid);
    return n;
  }

  public boolean isEmpty() { return _work._len==0; }
  public boolean on(Node n) { return _on.test(n._uid); }
  public void clear() { _work.clear(); _on.clear(); }
  @Override public String toString() { return _name+_on.toString(); }
}
