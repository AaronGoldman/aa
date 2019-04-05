package com.cliffc.aa.type;

import java.util.HashMap;

// Bits supporting a lattice; immutable; hash-cons'd.
public class BitsAlias extends Bits {
  // Intern: lookup and return an existing Bits or install in hashmap and
  // return a new Bits.  Overridden in subclasses to make type-specific Bits.
  private static HashMap<BitsAlias,BitsAlias> INTERN = new HashMap<>();
  private static BitsAlias FREE=null;
  @Override BitsAlias make_impl(int con, long[] bits ) {
    BitsAlias b1 = FREE;
    if( b1 == null ) b1 = new BitsAlias();
    else FREE = null;
    b1.init(con,bits);
    BitsAlias b2 = INTERN.get(b1);
    if( b2 != null ) { FREE = b1; return b2; }
    else { INTERN.put(b1,b1); return b1; }
  }

  // Have to make a first BitsAlias here; thereafter the v-call to make_impl
  // will make more on demand.  But need the first one to make a v-call.
  static final BitsAlias FULL = new BitsAlias().make_impl(-2,new long[]{-1});
  static final BitsAlias NZERO= make0(-2,new long[]{-2});
  public static final BitsAlias NIL  = make0(0);
  @Override public BitsAlias FULL() { return FULL; }

  static BitsAlias make0( int con, long[] bits ) { return (BitsAlias)FULL.make(con,bits); }
  static BitsAlias make0( int... bits ) { return (BitsAlias)FULL.make(bits); }
  static BitsAlias make0( int bit ) { return (BitsAlias)FULL.make(bit); }
  @Override public BitsAlias dual() { return (BitsAlias)super.dual(); }
  public BitsAlias meet( BitsAlias bs ) { return (BitsAlias)super.meet(bs); }
  @Override public BitsAlias clear(int i) { return (BitsAlias)super.clear(i); }
    
}
