package com.cliffc.aa.tvar;

import static com.cliffc.aa.AA.unimpl;

/** A type error.
 *
 */
public class TVErr extends TV3 {

  // -------------------------------------------------------------
  @Override void _union_impl(TV3 that) {
    if( !(that instanceof TVErr err) ) throw unimpl();
    throw unimpl();
  }

  @Override boolean _unify_impl(TV3 that, boolean test ) {
    throw unimpl();
  }
}
