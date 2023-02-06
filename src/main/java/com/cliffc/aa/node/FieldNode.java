package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.tvar.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.Util;

import static com.cliffc.aa.AA.unimpl;

// Takes a static field name, a TypeStruct and returns the field value.
// Basically a ProjNode except it does lookups by field name in TypeStruct
// instead of by index in TypeTuple.
//
// Fields with a fixed name either lookup in the incoming self record or in the
// prototype, based on a Parser flag.
//
// Fields with the "_" name resolve locally only via H-M matching to one choice
// field.  Once resolved the Field name changes to match.

public class FieldNode extends Node implements Resolvable {
  
  // Field being loaded from a TypeStruct.  If "_", the field name is inferred
  // from amongst the field choices.  If not present, then error.
  public       String _fld;
  // Field lookup is strictly in the clazz and not locally.
  public final boolean _clz;
  // Where to report errors
  public final Parse _bad;

  public FieldNode(Node struct, String fld, boolean clz, Parse bad) {
    super(OP_FIELD,struct);
    // A plain "_" field is a resolving field
    _fld = Util.eq(fld,"_") ? ("&"+_uid).intern() : fld;
    _bad = bad;
    _clz = clz;
    assert !(clz && is_resolving()); // One or the other for now
  }

  @Override public String xstr() { return "."+(is_resolving() ? "_" : _fld); }   // Self short name
  String  str() { return xstr(); } // Inline short name
  @Override public boolean is_resolving() { return Resolvable.is_resolving(_fld); }
  @Override public String fld() { assert !is_resolving(); return _fld; }
  // Set the resolved field label
  @Override public String resolve(String label) {
    unelock();                  // Hash changes since label changes
    String old = _fld;
    _fld = label;
    add_flow();
    return old;
  }
  
  @Override public TV3 match_tvar() { return tvar(0); }
  
  @Override public Type value() {
    Type t = val(0);
    if( t==Type.ANY || t==Type.ALL ) return t;
    // Here down, always return +/- SCALAR not ANY/ALL
    if( is_resolving() ) {
      // Pre-HMT, dunno which one, use meet.
      // Still resolving, use the join of all fields.
      boolean lo = _tvar==null || Combo.HM_AMBI;
      if( t instanceof TypeStruct ts )
        return lo ? meet(ts) : join(ts);
      return t.oob(TypeNil.SCALAR);
    }

    // Clazz or local struct ?
    Type tstr = null;
    if( _clz ) {
      StructNode clazz = clzz(t);
      if( clazz !=null ) {
        tstr = clazz._val;      // Value from clazz
        // Add a dep edge to the clazz, so value changes propagate permanently
        if( len()==2 ) assert in(1)==clazz;
        else add_def(clazz);
      } // Else clazz not defined, no clazz, no struct to field from
    } else {
      tstr = t;                 // Value direct from input
    }
    // Hit on a field
    if( tstr instanceof TypeStruct ts && ts.find(_fld)!= -1 )
      return ts.at(_fld).join(TypeNil.SCALAR).meet(TypeNil.XSCALAR);
    return (tstr==null ? t : tstr).oob(TypeNil.SCALAR);
  }

  private static Type meet(TypeStruct ts) { Type t = TypeNil.XSCALAR; for( TypeFld t2 : ts )  t = t.meet(t2._t); return t; }
  private static Type join(TypeStruct ts) {
    Type t = TypeNil.SCALAR;
    for( TypeFld t2 : ts )
      t = t.join( t2._t instanceof TypeFunPtr tfp2  ? tfp2.make_from(tfp2.fidxs().dual()) : t2._t );
    return t.meet(TypeNil.XSCALAR);
  }

  // Checks is_err from HMT from StructNode.
  // Gets the T2 from the base StructNode.
  // Gets the StructNode from the aliases - needs the actual struct layout
  private Type missing_field() {
    throw unimpl();
  }

  @Override public Node ideal_reduce() {
    if( is_resolving() ) return null;
    
    // Back-to-back SetField/Field
    if( in(0) instanceof SetFieldNode sfn && sfn.err(true)==null ) {
      if( Util.eq(_fld, sfn._fld) ) {
        if( sfn.val(1).isa(_val) ) return sfn.in(1); // Same field, use same
        else sfn.in(1).deps_add(this);
      } else {
        return Env.GVN.add_reduce(set_def(0, sfn.in(0))); // Unrelated field, bypass
      }
    }

    // Back-to-back CLZ-Struct/Field
    StructNode str = in(0) instanceof StructNode str0 ? str0 : clzz(val(0));
    // Back-to-back Struct/Field
    if( str!=null && str.err(true)==null ) {
      int idx = str.find(_fld);
      if( idx >= 0 ) {
        if( str.val(idx).isa(_val) ) return str.in(idx);
        else str.deps_add(this); // Revisit if input changes
      }
    } else {
      in(0).deps_add(this); // Revisit if input changes
    }

    return null;
  }

  // Prototype or null
  private static StructNode clzz(Type t) {
    return switch( t ) {
    case TypeInt ti -> PrimNode.ZINT;
    case TypeFlt tf -> PrimNode.ZFLT;
    case TypeStruct ts -> Env.PROTOS.get(ts._clz);  // CLZ from instance
    // TODO: XNIL uses the INT clazz.
    case TypeNil xnil -> xnil==TypeNil.XNIL ? PrimNode.ZINT : null;
    // Other, like SCALAR, does not have a known CLZ
    default -> null;
    };
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
          lphi.add_def(Env.GVN.add_work_new(new FieldNode(phi.in(i),_fld,_clz,_bad)));
        subsume(lphi);
        return lphi;
      }
    }

    return null;
  }

  @Override public boolean has_tvar() { return true; }

  @Override public boolean unify( boolean test ) {
    boolean progress = false;

    TV3 tv0 = tvar(0);          // If an instance field, need the input struct
    // Clazz fields do clazz lookups, expect clazz structures
    if( _clz ) {
      switch( tv0 ) {
      case TVClz clz -> tv0 = clz.clz(); // Clazz part from a clazzed TV
      case TVLeaf leaf -> {              // Expand to a clazzed TV
        StructNode proto = clzz(val(0)); // Existing prototypes for int/flt/named-clazz-types
        if( proto == null ) {            // Unknown inferred clazz
          if( test ) return true;        // Always progress
          tv0 = new TVStruct(true, new String[]{_fld}, new boolean[]{true}, new TV3[]{tvar()}, true);
          TVClz clz = new TVClz((TVStruct)tv0, new TVLeaf());
          progress = leaf.unify(clz, test);
        } else {
          tv0 = proto.tvar();
        }
      }
      case TVErr err -> { return false; }
      default -> throw unimpl();
      };
    }

    // Errors have a struct to unify against
    if( tv0 instanceof TVErr terr )
      tv0 = terr.as_struct();

    // Still not a struct?  Make one, add field
    TVStruct str;
    if( tv0 instanceof TVStruct str0 ) str = str0;
    else {
      if( test ) return true;
      TVStruct inst = new TVStruct(true, new String[]{_fld}, new TV3[]{tvar()}, true);
      progress |= tv0.unify(str=inst,test);
    }
    
    // If resolving, cannot do a field lookup.  Attempt resolve first.
    if( is_resolving() ) {
      if( Combo.HM_AMBI ) return false; // Failed earlier, can never resolve
      progress = try_resolve(str,test);
      if( is_resolving() || test ) return progress;
      str = (TVStruct)str.find();
    }
    assert !is_resolving();

    // Look up field normally
    TV3 fld = str.arg(_fld);
    if( fld!=null )           // Unify against a pre-existing field
      return tvar().unify(fld, test) | progress;

    // If field is doing overload resolution, inject even if rec is closed
    if( is_resolving() ) {
      if( test ) return true;
      throw unimpl();
    }
    
    // If the field is resolved, and not in struct and not in proto and the
    // struct is closed, then the field is missing.
    if( !str.is_open() ) {
      throw unimpl();           // Missing field
    }

    //// Add the field, make progress
    //if( !test ) str.add_fld(_fld,tvar());
    //return true;
    throw unimpl();
  }

  public static TVStruct tv_clz(Type t) {
    String clz = switch( t ) {
    case TypeInt ti -> "int:";
    case TypeFlt tf -> "flt:";
    case TypeNil tn -> tn==TypeNil.XNIL ? "int:" : null;
    default -> null;
    };
    return (TVStruct)Env.PROTOS.get(clz).tvar();
  }
  
  private boolean try_resolve( TVStruct str, boolean test ) {
    // If struct is open, more fields might appear and cannot do a resolve.
    TV3 self = tvar();
    if( !str.is_open() ) {
      if( trial_resolve(true, self, str, str, test) ) return true;
      // No progress, try again if self changes
      if( !test ) self.deps_add_deep(this);
    }
    // Progress if field is missing
    if( test ) return str.arg(_fld)==null;
    str.deps_add_deep(this);    // Try again if str closes
    // Add unresolved field if not already there (even if closed)
    return str.arg(_fld)==null && str.add_fld(_fld,Oper.is_oper(_fld),self); 
  }

  @Override public ErrMsg err( boolean fast ) {
    Ary<String> errs = tvar()._errs;
    if( errs==null ) return null;
    if( fast ) return ErrMsg.FAST;
    if( errs.len()>1 ) throw unimpl();
    if( tvar(0) instanceof TVLeaf )
      return ErrMsg.unresolved(_bad,"Not a struct loading field "+_fld);
    return tvar(0).as_struct().err_resolve(in(0),_bad, errs.at(0));
  }

  @Override public int hashCode() { return super.hashCode()+_fld.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof FieldNode fld) ) return false;
    return Util.eq(_fld,fld._fld);
  }

}
