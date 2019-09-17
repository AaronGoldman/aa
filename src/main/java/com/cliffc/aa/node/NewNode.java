package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;

// TODO: fix recursive types
//
// Types are extended via NewNode; TypeStructs only created here - but merged
// at Phis so new variants appear at every MEET.  But depth only increases here.
//
// Cyclic types created to approx when more than CUTOFF_DEPTH number of the
// same NewNodes have added TypeStructs to the same type.
//
// Approx at the deepest point: top-level triggers at depth e.g. 5, so find the
// depth(1) TypeStruct and the depth(0) TS points point back to the depth(1) TS
// (same as approx() does now), and MEET the depth(0) over the depth(1).
//
// NewNode types can be MEET at Phis; such merged {NewNodes/TypeStructs} count
// as a "same NewNode" for cyclic depth purposes.  This implies we need to UF
// NewNode TS types at MEETs.
//
// Implementation: TS types pick up a NewNode UF field.  Literally a NewNode
// ptr, and NewNode has a UF ptr also.  Standard UF; "union" happens when 2 TS
// types are meet with different NNs.  When computing depth, can use the UF NN
// id to look for repeats, and report back the depth(0) to the depth(1).
// 
// At compilation unit end, can wipe out all TS with non-null NN?



// Make a new object of given type.  Returns both the pointer and the TypeObj
// but NOT the memory state.
public class NewNode extends Node {
  // Unique alias class, one class per unique memory allocation site.
  // Only effectively-final, because the copy/clone sets a new alias value.
  private int _alias;           // Alias class
  TypeStruct _ts;               // Result struct (may be named)
  private TypeObj _obj;         // Optional named struct
  TypeMemPtr _ptr;              // Cached pointer-to-_obj
  private NewNode _nuf;         // U-F during cyclic type discovery

  public NewNode( Node[] flds, TypeObj obj ) {
    super(OP_NEW,flds);
    assert flds[0]==null;       // no ctrl field
    _alias = BitsAlias.new_alias(BitsAlias.REC);
    TypeStruct ts = (TypeStruct)obj.base();
    // Reconstruct obj with 'this' _nuf
    _ts = TypeStruct.make(ts._flds,ts._ts,ts._finals,this);
    // If a TypeName wrapper, rewrap
    if( obj instanceof TypeName ) obj = ((TypeName)obj).make(_ts);
    else obj = _ts;
    _obj = obj;
    _ptr = TypeMemPtr.make(_alias,obj);
  }
  private int def_idx(int fld) { return fld+1; }
  Node fld(int fld) { return in(def_idx(fld)); }
  
  // Called when folding a Named Constructor into this allocation site
  void set_name( GVNGCM gvn, TypeName to ) {
    assert to.base().isa(_ts); // Cannot change the target fields, just the name
    assert ((TypeStruct)to.base())._nuf == this;
    Type oldt = gvn.type(this);
    gvn.unreg(this);
    _ts = (TypeStruct)to.base();
    _obj = to;
    _ptr = TypeMemPtr.make(_alias,to);
    if( !(oldt instanceof TypeMemPtr) )  throw com.cliffc.aa.AA.unimpl();
    TypeMemPtr nameptr = _ptr.make(to.make(((TypeMemPtr)oldt)._obj));
    gvn.rereg(this,nameptr);
  }
  
  String xstr() { return "New*"+_alias; } // Self short name
  String  str() { return "New"+_ptr; } // Inline less-short name

  public NewNode ufind() {
    if( _nuf == null ) return this;
    if( _nuf._nuf == null ) return _nuf;
    throw com.cliffc.aa.AA.unimpl();
  }
  // Union 'this' and 'nuf'.  Both are set bases.
  // Keep the smallest _uid for the set base, and return the base.
  public NewNode union( NewNode nuf ) {
    NewNode nuf0 = ufind(), nuf1 = nuf.ufind();
    if( nuf0 == nuf1 ) return nuf0;
    assert nuf0._nuf==null && nuf1._nuf==null; // Both are set bases
    return (nuf0._uid < nuf1._uid) ? (nuf1._nuf = nuf0) : (nuf0._nuf = nuf1);
  }
  
  @Override public Node ideal(GVNGCM gvn) { return null; }
  
  // Produces a TypeMemPtr
  @Override public Type value(GVNGCM gvn) {
    // Gather args and produce a TypeStruct
    Type[] ts = new Type[_ts._ts.length];
    for( int i=0; i<_ts._ts.length; i++ )
      ts[i] = gvn.type(fld(i)).bound(_ts._ts[i]); // Limit to Scalar results
    TypeStruct newt = TypeStruct.make(_ts._flds,ts,_ts._finals,this);

    // Check for TypeStructs with this same NewNode U-F types occuring more
    // than CUTOFF deep, and fold the deepest ones onto themselves to limit the
    // type depth.  If this happens, the types become recursive with the
    // approximations happening at the deepest points.
    TypeStruct ts2 = newt.approx2(ufind()._uid,CUTOFF);
    if( ts2 != null ) throw com.cliffc.aa.AA.unimpl();
    
    //// Get the existing type, without installing if missing because blows the
    //// "new NewNode" assert if this node gets replaced during parsing.
    //Type oldnnn = gvn.self_type(this);
    //// Get the struct-part of the old type for cycle checking
    //TypeStruct oldt = newt;
    //if( oldnnn != null ) {
    //  TypeObj tobj = ((TypeMemPtr)oldnnn)._obj;
    //  if( _obj instanceof TypeName ) {
    //    assert ((TypeName)tobj)._name == ((TypeName)_obj)._name; // == not equals
    //    oldt = (TypeStruct)((TypeName)tobj)._t;
    //  } else oldt = (TypeStruct)tobj;
    //}
    //
    //// Approximate infinite types
    //TypeStruct apxt = newt;
    //if( approx(newt,oldt) ) {   // If approximating, need to keep meeting old and new
    //  TypeStruct apxt1 = newt.approx(oldt);
    //  apxt = (TypeStruct) (gvn._opt_mode==2 ? apxt1.meet(oldt) : apxt1.join(oldt));
    //}
    //assert apxt.depth() <= CUTOFF+1 || apxt.depth() >= 9999;
    //
    TypeObj res = _obj instanceof TypeName ? ((TypeName)_obj).make(newt) : newt;
    return TypeMemPtr.make(_alias,res);
  }

  // NewNodes can participate in cycles, where the same structure is appended
  // to in a loop until the size grows without bound.  If we detect this we
  // need to approximate a new cyclic type.
  private final static int CUTOFF=5; // Depth of types before we start forcing approximations
  public static boolean approx( TypeStruct newt, TypeStruct oldt ) {
    return newt != oldt && newt.contains(oldt) && oldt.depth() > CUTOFF &&
            (newt.above_center() || !oldt.above_center());
  }

  @Override public Type all_type() { return _ptr; }

  // Clones during inlining all become unique new sites
  @Override NewNode copy(GVNGCM gvn) {
    // Split the original '_alias' class into 2 sub-classes
    NewNode nnn = (NewNode)super.copy(gvn);
    nnn._alias = BitsAlias.new_alias(_alias); // Children alias classes, split from parent
    nnn._ptr = TypeMemPtr.make(nnn._alias,_obj);
    // The original NewNode also splits from the parent alias
    Type oldt = gvn.type(this);
    gvn.unreg(this);
    _alias = BitsAlias.new_alias(_alias);
    _ptr = TypeMemPtr.make(_alias,_obj);
    gvn.rereg(this,oldt);
    return nnn;
  }

  @Override public int hashCode() { return super.hashCode()+ _ptr._hash; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof NewNode) ) return false;
    NewNode nnn = (NewNode)o;
    return _ptr==nnn._ptr;
  }
}
