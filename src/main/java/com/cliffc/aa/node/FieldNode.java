package com.cliffc.aa.node;

import com.cliffc.aa.Combo;
import com.cliffc.aa.Env;
import com.cliffc.aa.Parse;
import com.cliffc.aa.tvar.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Util;

import static com.cliffc.aa.AA.unimpl;

// Takes a static field name, a TypeStruct and returns the field value.
// Basically a ProjNode except it does lookups by field name in TypeStruct
// instead of by index in TypeTuple.
public class FieldNode extends Node {
  
  // Field being loaded from a TypeStruct.  If null, the field name is inferred
  // from amongst the field choices.  If not-null and not present, then an error.
  public       String _fld;
  public final Parse _bad;

  public FieldNode(Node struct, String fld, Parse bad) {
    super(OP_FIELD,struct);
    _fld = fld==null ? ("&"+_uid).intern() : fld;
    _bad = bad;
  }

  @Override public String xstr() { return "."+(is_resolving() ? "_" : _fld); }   // Self short name
  String  str() { return xstr(); } // Inline short name
  static boolean is_resolving(String fld) { return fld.charAt(0)=='&'; }
  boolean is_resolving() { return is_resolving(_fld); }

  @Override public Type value() {
    Type t = val(0);
    String sclz;
    // TODO: proper semantics?  For now, mimic int
    if( t==TypeNil.XNIL ) {
      sclz = "int:";
    } else {
      // Input is not a struct, so error return
      if( !(t instanceof TypeStruct ts) )
        return t.oob();
      // Mid-resolve?  No field name yet.
      if( is_resolving() )
        // Pre-HMT, dunno which one, use meet.
        // Still resolving, use the join of all fields.
        return (_tvar==null || Combo.HM_AMBI) ? meet(ts) : join(ts);
      // Normal field lookup
      TypeFld fld = ts.get(_fld);
      // Hit with field name
      if( fld!=null ) return fld._t;
      // Missed, but has a clazz?  Try the prototype lookup
      sclz = ts.clz();
      if( sclz.isEmpty() )
        return missing_field();
    }
    throw unimpl();
  }

  private static Type meet(TypeStruct ts) { Type t = TypeNil.XSCALAR; for( TypeFld t2 : ts )  t = t.meet(t2._t); return t; }
  private static Type join(TypeStruct ts) {
    Type t = TypeNil.SCALAR;
    for( TypeFld t2 : ts )
      t = t.join( t2._t instanceof TypeFunPtr tfp2  ? tfp2.make_from(tfp2.fidxs().dual()) : t2._t );
    return t;
  }

  // Checks is_err from HMT from StructNode.
  // Gets the T2 from the base StructNode.
  // Gets the StructNode from the aliases - needs the actual struct layout
  private Type missing_field() {
    throw unimpl();
  }

  
  @Override public Node ideal_reduce() {
    // Back-to-back SetField/Field
    if( in(0) instanceof SetFieldNode sfn && sfn.err(true)==null )
      return Util.eq(_fld, sfn._fld)
        ? sfn.in(1)             // Same field, use same
        : set_def(0, sfn.in(0)); // Unrelated field, bypass

    return null;
  }

  @Override public Node ideal_grow() {
    // Load from a memory Phi; split through in an effort to sharpen the memory.
    // TODO: Only split thru function args if no unknown_callers, and must make a Parm not a Phi
    // TODO: Hoist out of loops.
    if( in(0) instanceof PhiNode phi ) {
      int fcnt=0;
      for( int i=1; i<phi.len(); i++ )
        if( phi.in(i)._op == OP_SETFLD ) fcnt++;
      if( fcnt>0 ) {
        Node lphi = new PhiNode(TypeNil.SCALAR,phi._badgc,phi.in(0));
        for( int i=1; i<phi.len(); i++ )
          lphi.add_def(Env.GVN.add_work_new(new FieldNode(phi.in(i),_fld,_bad)));
        subsume(lphi);
        return lphi;
      }
    }

    return null;
  }

  @Override public boolean has_tvar() { return true; }

  @Override public boolean unify( boolean test ) {
    TV3 self = tvar();
    TV3 rec = tvar(0);
    assert !(rec instanceof TVPtr); // No ptrs here, just structs

    // Add struct-ness if needed
    TVStruct str;
    if( !(rec instanceof TVStruct str0) ) {
      if( test ) return true;
      throw unimpl();
    } else {
      str = str0;
    }

    // Look up field
    TV3 fld = str.arg(_fld);
    if( fld!=null )           // Unify against a pre-existing field
      return fld.unify(self, test);

    //// Add the field
    //if( rec.is_obj() && rec.is_open() ) {
    //  if( !test ) rec.add_fld(_fld,self);
    //  return true;
    //}
    //// Closed/non-record, field is missing
    //return self.set_err(("Missing field "+_fld).intern(),test);
    throw unimpl();
  }

  @Override public int hashCode() { return super.hashCode()+_fld.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof FieldNode fld) ) return false;
    return Util.eq(_fld,fld._fld);
  }

}
