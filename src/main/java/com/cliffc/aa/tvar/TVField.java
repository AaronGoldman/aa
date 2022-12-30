package com.cliffc.aa.tvar;

import java.util.HashMap;

import static com.cliffc.aa.AA.unimpl;

/** Field reference from a Struct
 */
public class TVField extends TV3 {
  
  public TVField( TV3 ptr ) { super(true,ptr); }

  // Unresolved field names; typically "&nnn" where `nnn` is the FieldNode id
  public static final HashMap<String,Resolvable> FIELDS = new HashMap<>();
  public static void reset_to_init0() { FIELDS.clear(); }

  // -------------------------------------------------------------
  @Override
  void _union_impl(TV3 that) {
    if( !(that instanceof TVBase base) ) throw unimpl();
    throw unimpl();
  }
  
  @Override boolean _unify_impl(TV3 that ) {
    throw unimpl();
  }

  @Override int eidx() { throw unimpl(); }

}
