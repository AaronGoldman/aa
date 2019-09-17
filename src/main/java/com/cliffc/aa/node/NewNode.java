package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;

// Make a new object of given type.  Returns both the pointer and the TypeObj
// but NOT the memory state.
public class NewNode extends Node {
  // Unique alias class, one class per unique memory allocation site.
  // Only effectively-final, because the copy/clone sets a new alias value.
  private int _alias;           // Alias class
  TypeStruct _ts;               // Result struct (may be named)
  private TypeObj _obj;         // Optional named struct
  private boolean _did_meet;
  TypeMemPtr _ptr;              // Cached pointer-to-_obj

  public NewNode( Node[] flds, TypeObj obj ) {
    super(OP_NEW,flds);
    assert flds[0]==null;       // no ctrl field
    _alias = BitsAlias.new_alias(BitsAlias.REC);
    _ts = (TypeStruct)obj.base();
    _obj = obj;
    _ptr = TypeMemPtr.make(_alias,_obj);
  }
  private int def_idx(int fld) { return fld+1; }
  Node fld(int fld) { return in(def_idx(fld)); }
  // Called when folding a Named Constructor into this allocation site
  void set_name( GVNGCM gvn, TypeName to ) {
    assert to.base().isa(_ts); // Cannot change the target fields, just the name
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
  @Override public Node ideal(GVNGCM gvn) { return null; }
  // Produces a TypeMemPtr
  @SuppressWarnings("StringEquality")
  @Override public Type value(GVNGCM gvn) {
    // Gather args and produce a TypeStruct
    Type[] ts = new Type[_ts._ts.length];
    for( int i=0; i<_ts._ts.length; i++ )
      ts[i] = gvn.type(fld(i)).bound(_ts._ts[i]); // Limit to Scalar results
    TypeStruct newt = TypeStruct.make(_ts._flds,ts,_ts._finals);

    // Get the existing type, without installing if missing because blows the
    // "new NewNode" assert if this node gets replaced during parsing.
    Type oldnnn = gvn.self_type(this);
    // Get the struct-part of the old type for cycle checking
    TypeStruct oldt = newt;
    if( oldnnn != null ) {
      TypeObj tobj = ((TypeMemPtr)oldnnn)._obj;
      if( _obj instanceof TypeName ) {
        assert ((TypeName)tobj)._name == ((TypeName)_obj)._name; // == not equals
        oldt = (TypeStruct)((TypeName)tobj)._t;
      } else oldt = (TypeStruct)tobj;
    }

    TypeStruct apxt= approx(newt,oldt); // Approximate infinite types
    if( _did_meet || apxt != newt ) {   // If approximating, need to keep meeting old and new
      _did_meet=true;       // If did meet once, need to keep doing it forever.
      apxt = (TypeStruct) (gvn._opt_mode==2 ? apxt.meet(oldt) : apxt.join(oldt));
    }

    TypeObj res = _obj instanceof TypeName ? ((TypeName)_obj).make(apxt) : apxt;
    return TypeMemPtr.make(_alias,res);
  }

  // NewNodes can participate in cycles, where the same structure is appended
  // to in a loop until the size grows without bound.  If we detect this we
  // need to approximate a new cyclic type.
  private final static int CUTOFF=5; // Depth of types before we start forcing approximations
  public static TypeStruct approx( TypeStruct newt, TypeStruct oldt ) {
    return newt != oldt && newt.contains(oldt) && oldt.depth() > CUTOFF
      ? (TypeStruct)newt.approx(oldt)
      : newt;
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
