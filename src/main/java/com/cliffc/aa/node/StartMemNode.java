package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.tvar.*;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeMem;

// Program memory start
public class StartMemNode extends Node {
  public StartMemNode(StartNode st) { super(OP_STMEM,st); }
  @Override public boolean is_mem() { return true; }
  @Override public Type value(GVNGCM.Mode opt_mode) { return TypeMem.ANYMEM; }
  @Override public TMem new_tvar() { return new TMem(this); }
  @Override public boolean unify( boolean test ) { return false; }
  @Override public TypeMem all_live() { return TypeMem.ALLMEM; }
  // StartMemNodes are never equal
  @Override public int hashCode() { return 123456789+2; }
  @Override public boolean equals(Object o) { return this==o; }
}
