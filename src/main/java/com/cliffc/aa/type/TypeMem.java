package com.cliffc.aa.type;

import com.cliffc.aa.util.SB;

import java.util.Arrays;
import java.util.BitSet;

/**
   Memory type; the state of all of memory; memory edges order memory ops.
   Produced at the program start, consumed by all function calls, consumed be
   Loads, consumed and produced by Stores.  Can be broken out in the "equiv-
   alence class" (Alias#) model of memory over a bulk memory to allow more fine
   grained knowledge.  Memory is accessed via Alias#s, where all TypeObjs in an
   Alias class are Meet together as an approximation.

   Conceptually, each alias# represents an infinite set of pointers - broken
   into equivalence classes.  We can split such a class in half - some pointers
   will go left and some go right, and where we can't tell we'll use both sets.
   Any alias set is a tree-like nested set of sets bottoming out in individual
   pointers.  The types are conceptually unchanged if we start using e.g. 2
   alias#s instead of 1 everywhere - we've just explicitly named the next layer
   in the tree-of-sets.

   Split an existing alias# in half, such that some ptrs point to one half or
   the other, and most point to either (or both).  Basically find all
   references to alias#X and add a new alias#Y paired with X - making all
   alias types use both equally.  Leave the base constructor of an X alias
   (some NewNode) alone - it still produces just an X.  The Node calling
   split_alias gets Y alone, and the system as a whole makes a conservative
   approximation that {XY} are always confused.  Afterwards we can lift the
   types to refine as needed.

   During iter()/pessimistic-GVN we'll have ptrs to a single New which splits -
   and this splits the aliases; repeated splitting induces a tree.  Some ptrs
   to the tree-root will remain, and represent conservative approximation as
   updates to outputs from all News.  We'll also have sharper direct ptrs
   flowing out, pointing to only results from a single New.  At the opto()
   point we'll not have any more confused types.

   CNC - Observe that the alias Trees on Fields applies to Indices on arrays as
   well - if we can group indices in a tree-like access pattern (obvious one
   being All vs some Constants).
*/
public class TypeMem extends Type<TypeMem> {

  // CNC - Add _any flag like TypeObj; allows for union/inter of alias sets.
  // Drop _aliases, use NBHML and directly map alias# to TypeObj.
  // Check rd_bar & if pass era-check use brooks-barrier version (if fail,
  // get an updated brooks-barrier version).

  // Mapping from alias#s to the current known alias state
  private TypeObj[] _aliases;
  // The "default" infinite mapping.  Everything past _aliases.length or null
  // maps to the default instead.  If the default is null, then the aliasing is
  // exact, and trying to read null is an error.
  private TypeObj _def;
  
  private TypeMem  (TypeObj def, TypeObj[] aliases ) { super(TMEM); init(def,aliases); }
  private void init(TypeObj def, TypeObj[] aliases ) {
    super.init(TMEM);
    _def = def;
    _aliases = aliases;
    assert check(def,aliases);
  }
  // "tight": no extra instances of default
  private static boolean check(TypeObj def, TypeObj[] aliases ) {
    if( def != null )
      for( TypeObj obj : aliases )
        if( obj==def )
          return false; // Extra instances of default; messes up canonical rep for hash-cons
    return aliases.length==0 || aliases[aliases.length-1] != def;
  }
  @Override int compute_hash() {
    int hash = TMEM + _def._hash;
    for( TypeObj obj : _aliases )  if( obj != null )  hash += obj._hash;
    return hash;
  }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeMem) ) return false;
    TypeMem tf = (TypeMem)o;
    if( _def != tf._def || _aliases.length != tf._aliases.length ) return false;
    for( int i=0; i<_aliases.length; i++ )
      if( _aliases[i] != tf._aliases[i] ) // note '==' and NOT '.equals()'
        return false;
    return true;
  }
  // Never part of a cycle, so the normal check works
  @Override public boolean cycle_equals( Type o ) { return equals(o); }
  @Override String str( BitSet dups ) {
    if( _aliases.length < BitsAlias.MAX_SPLITS )
      throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    SB sb = new SB();
    sb.p("[");
    if( _def != TypeObj.OBJ )
      sb.p("mem#:").p(_def.toString()).p(",");
    for( int i=0; i<_aliases.length; i++ )
      if( _aliases[i] != null )
        sb.p(i).p("#:").p(_aliases[i].toString()).p(",");
    return sb.p("]").toString();
  }
  // Alias must exist
  public TypeObj at0(int alias) {
    if( alias >= _aliases.length ) return _def;
    TypeObj obj = _aliases[alias];
    return obj==null ? _def : obj;
  }
  
  private static TypeMem FREE=null;
  @Override protected TypeMem free( TypeMem ret ) { _aliases=null; FREE=this; return ret; }
  private static TypeMem make( TypeObj def, TypeObj[] aliases ) {
    TypeMem t1 = FREE;
    if( t1 == null ) t1 = new TypeMem(def,aliases);
    else { FREE = null;       t1.init(def,aliases); }
    TypeMem t2 = (TypeMem)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }

  // Precise single alias
  public static TypeMem make(int alias, TypeObj oop ) {
    assert oop!=null;
    TypeObj[] oops = new TypeObj[alias+1];
    oops[alias] = oop;
    return make(TypeObj.OBJ,oops);
  }
  // Canonicalize memory before making
  static TypeMem make0( TypeObj def, TypeObj[] objs ) {
    assert objs.length >= BitsAlias.MAX_SPLITS; // Already updated
    // Remove elements redundant with the default value
    int len = objs.length;
    for( int i=0; i<len; i++ )  if( objs[i]==def )  objs[i]=null;
    while( len > 0 && objs[len-1]==null ) len--;
    if( len < objs.length ) objs = Arrays.copyOf(objs,len);
    return make(def,objs);
  }

  public  static final TypeMem MEM = make(TypeObj.OBJ,new TypeObj[0]);
  public  static final TypeMem XMEM = MEM.dual();
          static final TypeMem MEM_STR = make(TypeStr.STR_alias,TypeStr.STR);
          static final TypeMem MEM_ABC = make(TypeStr.ABC_alias,TypeStr.ABC);
  static final TypeMem[] TYPES = new TypeMem[]{MEM,MEM_STR};

  // All mapped memories remain, but each memory flips internally.
  @Override protected TypeMem xdual() {
    TypeObj[] oops = new TypeObj[_aliases.length];
    for(int i=0; i<_aliases.length; i++ )
      if( _aliases[i] != null )
        oops[i] = (TypeObj)_aliases[i].dual();
    return new TypeMem((TypeObj)_def.dual(), oops);
  }
  @Override protected Type xmeet( Type t ) {
    if( t._type != TMEM ) return ALL; //
    TypeMem tf = (TypeMem)t;
    if(    _aliases.length < BitsAlias.MAX_SPLITS ) throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    if( tf._aliases.length < BitsAlias.MAX_SPLITS ) throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    // Meet of default values, meet of element-by-element.
    TypeObj def = (TypeObj)_def.meet(tf._def);
    int len = Math.max(_aliases.length,tf._aliases.length);
    TypeObj[] objs = new TypeObj[len];
    for( int i=0; i<len; i++ )
      objs[i] = (TypeObj)at0(i).meet(tf.at0(i));
    return make0(def,objs);
  }

  // Meet of all possible loadable values
  public TypeObj ld( TypeMemPtr ptr ) {
    if(    _aliases.length < BitsAlias.MAX_SPLITS ) throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    boolean any = ptr.above_center();
    TypeObj obj = TypeObj.OBJ;
    if( !any ) obj = (TypeObj)TypeObj.OBJ.dual();
    for( int alias : ptr._aliases ) {
      TypeObj x = at0(alias);
      obj = (TypeObj)(any ? obj.join(x) : obj.meet(x));
    }
    return obj;
  }

  // Meet of all possible storable values, after updates
  public TypeMem st( TypeMemPtr ptr, String fld, int fld_num, Type val ) {
    if(    _aliases.length < BitsAlias.MAX_SPLITS ) throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    assert val.isa_scalar();
    TypeObj[] objs = new TypeObj[_aliases.length];
    for( int alias : ptr._aliases )
      objs[alias] = at0(alias).update(fld,fld_num,val);
    return make0(_def,objs);
  }

  // Merge two memories with no overlaps.  This is similar to a st(), except
  // updating an entire Obj not just a field, and not a replacement.  The
  // given memory is precise - the default field is ignorable.
  public TypeMem merge( TypeMem mem ) {
    if(     _aliases.length < BitsAlias.MAX_SPLITS ) throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    if( mem._aliases.length < BitsAlias.MAX_SPLITS ) throw com.cliffc.aa.AA.unimpl(); // Might need to split this guy
    // Check no overlap
    int  len =     _aliases.length;
    int mlen = mem._aliases.length;
    for( int i=0; i<mlen; i++ ) {
      if( mem._aliases[i]==null ) continue;
      assert i >= len || _aliases[i]==null || _aliases[i]==mem._aliases[i];
    }
    TypeObj[] objs = Arrays.copyOf(_aliases,Math.max(len,mlen));
    for( int i=0; i<mlen; i++ )
      if( mem._aliases[i]!=null)
        objs[i] = mem._aliases[i];
    return make(_def,objs);
  }
  
  @Override public boolean above_center() { return _def.above_center(); }
  @Override public boolean may_be_con()   { return false;}
  @Override public boolean is_con()       { return false;}
  @Override public boolean must_nil() { return false; } // never a nil
  @Override Type not_nil() { return this; }
}
