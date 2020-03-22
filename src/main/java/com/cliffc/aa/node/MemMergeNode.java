package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.AryInt;
import com.cliffc.aa.util.SB;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.BitSet;

// Merge a lot of TypeObjs into a TypeMem.  Each input is from a different
// alias.  Each collection represents the whole of memory, with missing parts
// coming in the alias#1 in slot 0, and no duplication.
//
// MemMerge is used by the parser to create the initial memory state.  While
// parsing the MemMerge is left "active" - not in the GVN tables - which means
// edges can be updated without e.g. rehashing in GVN.  Post parsing follows
// the normal edge-modification rules.  This means there are two versions of
// some routines - the "in GVN tables" and "out of GVN tables" versions.
//
// Any alias can be split from its parent, and the parent remains to handle the
// unsplit parts.  New aliases can be added; when added they effectively
// pre-split up the alias tree to the root, and take their initial value from
// their deepest parent.  All aliases are assumed to be precisely independent
// (equivalence class).  Alias parents are typically assumed to have more
// future unknown splits in them, so merges of all known children still expect
// to have a parent merge.
//
// As a special case, MemMerge will take the results of an all-call-memory in
// alias#1 slot 0 - and will gradually widen general memory around the call as
// the call memory sharpens.  This is specifically for SESE call behavior.
public class MemMergeNode extends Node {

  // Alias equivalence class matching each input.  Sorted.
  // A map from idx# to Alias, aligned with the Nodes.
  private AryInt _aliases;

  // Alias-to-idx#, for fast reverse lookup.
  private AryInt _aidxes;

  public MemMergeNode( Node mem ) {
    super(OP_MERGE,mem);
    // Forward mapping from idx# to alias#.  Slot 0 is always BitsAlias.ALL
    _aliases = new AryInt(new int[]{BitsAlias.ALL});
    // Reverse mapping from alias# to idx#.  BitsAlias.ALL is always slot 0,
    // and null (alias 0) is never a thing.
    _aidxes  = new AryInt(new int[]{-1,0});
  }
  // A first alias
  public MemMergeNode( Node mem, Node obj, int alias ) {
    this(mem);
    create_alias_active(alias,obj,null);
  }
  @Override public void reset_to_init1(GVNGCM gvn) {
    gvn.unreg(this);
    for( int i=1; i<_defs._len; i++ )
      if( !in(i).is_prim() )
        remove0(i--,gvn);
    _aliases.set_len(_defs._len);
  }

  @Override String str() {
    SB sb = new SB().p('[');
    for( int i=0;i<_defs._len; i++ )
      sb.p(in(i)==null ? -1 : in(i)._uid).p(":#").p(alias_at(i)).p(", ");
    return sb.unchar().unchar().p(']').toString();
  }

  private Node mem() { return in(0); } // Phat/Wide mem
  int alias_at(int idx) { return _aliases.at(idx); }

  // Index# for Alias#.  Returns 0 if no exact match.
  public int alias2idx( int alias ) { return _aidxes.atX(alias); }
  private void set_alias2idx( int alias, int idx ) { _aidxes.setX(alias,idx); }

  // Index# for Alias#, or nearest enclosing alias parent
  private int find_alias2idx( int alias ) {
    int idx;
    while( (idx=alias2idx(alias)) == 0 && alias != BitsAlias.ALL )
      alias = BitsAlias.parent(alias);
    return idx;
  }

  private boolean check() {
    for( int i=1; i<_aliases._len; i++ )
      if( _aliases.at(i-1) >= _aliases.at(i) )
        return false;
    return true;
  }

  // Index# for Alias#, creating as needed.  If created the new slot will be null.
  public int make_alias2idx( int alias ) {
    assert check();
    // Insert in alias index order
    int iidx = _aliases.binary_search(alias);
    if( _aliases.atX(iidx)==alias ) return iidx; // Exact match
    insert(iidx,null);          // Initial value
    _aliases.insert(iidx,alias);// Matching idx# to alias map
    // Every alias after the insertion point has its index upped by 1
    _aidxes.map_update(i -> i >= iidx ? i+1 : i);
    set_alias2idx(alias,iidx);// Reverse map alias# to idx#
    assert check();
    return iidx;
  }

  // Remove a DEF, and update everything
  public void remove0( int xidx, GVNGCM gvn ) {
    assert check();
    remove(xidx,gvn);                  // Remove def, preserving order
    int alias = _aliases.remove(xidx); // Remove alias mapping, preserving order
    // Every alias after the removal point has its index downed by 1
    _aidxes.map_update(i -> i >= xidx ? i-1 : i);
    set_alias2idx(alias,0);     // Remove reverse mapping alias# to idx#
  }

  // Node for an alias, using the nearest enclosing parent alias as needed
  Node alias2node( int alias ) { return in(find_alias2idx(alias)); }

  // Node for an alias, using the nearest enclosing parent alias as needed.
  // Fails with NULL if there are any children of the parent.
  // Used by Loads, which can bypass exact aliases.
  Node alias2node_precise( int alias ) {
    int idx = find_alias2idx(alias);
    for( int j=idx+1; j<_defs._len; j++ )
      if( BitsAlias.is_parent(alias,alias_at(j)) )
        return null;
    return in(idx);
  }

  // Precise alias input
  public Node active_obj(int alias) {
    assert alias > 1 && !BitsAlias.is_parent(alias); // No already-split aliases
    return in(find_alias2idx(alias));        // Alias
  }

  // Mid-iter call, will need to unreg/rereg
  public Node obj(int alias, GVNGCM gvn) {
    assert gvn.touched(this) && alias > 1; // Only if this MemMerge is not active
    Type oldt = gvn.unreg(this);
    int idx = make_alias2idx(alias);         // Make a spot for this alias
    Node obj = in(idx);                      // Get current def
    if( obj == null ) {                      // No prior alias
      obj = in(find_alias2idx(BitsAlias.parent(alias)));
      set_def(idx, obj, null);  // Set in immediate alias parent
    }
    gvn.rereg(this,oldt);
    return obj;
  }

  // Lookup a node index, given a TypeMemPtr.  Only works if the given alias
  // has not been split into parts
  Node obj( TypeMemPtr tmp, GVNGCM gvn ) {
    int alias = tmp._aliases.abit();
    if( alias == -1 ) throw AA.unimpl(); // Handle multiple aliases, handle all/empty
    return obj(alias,gvn);
  }

  // Create a new alias slot with initial value for an active this
  public void create_alias_active( int alias, Node n, GVNGCM gvn ) {
    assert gvn==null || (gvn.type(n) instanceof TypeObj || gvn.type(n) instanceof TypeMem);
    assert alias2idx(alias) == 0;    // No dups
    int idx = make_alias2idx(alias); // Get the exact alias
    assert in(idx)==null;            // Must be newly created, so set to null
    set_def(idx,n,null);             // No need for GVN since null
  }

  // An imprecise store updates all aliases
  public void st( StoreNode st, GVNGCM gvn ) {
    assert !gvn.touched(this);
    Type tadr = gvn.type(st.adr());
    if( tadr instanceof TypeMemPtr ) {
      TypeMemPtr ptr = (TypeMemPtr)tadr;
      BitSet bs = ptr._aliases.tree().plus_kids(ptr._aliases);
      for( int alias = bs.nextSetBit(0); alias >= 0; alias = bs.nextSetBit(alias+1) ) {
        int idx = make_alias2idx(alias);
        set_def(idx,st,gvn);
      }
    } else {
      if( tadr.above_center() ) return; // Assume nothing is being stored into
      assert TypeMemPtr.OOP.isa(tadr);  // Address might lift to a valid ptr
      // Assume all RECORD aliases are stomped over.  Very conservative.
      // Reset this MemMerge to having just the 'st' in slot#1, alias#2.
      while( _defs._len > 1 ) pop(gvn);
      _aliases.set_len(1);
      _aidxes .set_len(BitsAlias.RECORD+1);
      add_def(st);
      _aliases.push(BitsAlias.RECORD);
      _aidxes .setX(BitsAlias.RECORD,1);
      assert check();
    }
  }


  // This MemMerge is 'active': not installed in GVN and free to have its edges
  // changed (by the Parser as new variables are discovered).  Make it
  // 'inactive' and ready for nested Node.ideal() calls.
  Node deactive( GVNGCM gvn ) {
    assert !gvn.touched(this) && _uses._len==0;
    for( int i=0; i<_defs._len; i++ ) {
      Node obj = in(i);
      assert gvn.touched(obj);  // No longer needed
    }
    return this;                // Ready for gvn.xform as a new node
  }

  @Override public Node ideal(GVNGCM gvn, int level) {
    if( is_prim() ) return null;
    assert _defs._len==_aliases._len;
    boolean live_stable = _live.isa(in(0)._live);
    // Get TypeObj of default alias
    Type t0x = gvn.type(in(0));
    TypeMem t0mem = t0x instanceof TypeMem ? (TypeMem)t0x : TypeMem.FULL; // Might be 'any' from Phi
    // Dead & duplicate inputs can be removed.
    boolean progress = false;
    for( int i=1; i<_defs._len; i++ ) {
      int alias = alias_at(i);
      // Get TypeObj of this slice
      Type ti = gvn.type(in(i));
      if( ti instanceof TypeMem )
        ti = ((TypeMem)ti).at(alias);
      assert ti instanceof TypeObj || ti==Type.ANY;
      // Check for incoming alias slice is dead, from a alive memmerge
      if( ti==TypeObj.XOBJ && !(in(i) instanceof ConNode) ) {
        set_def(i,gvn.con(TypeObj.XOBJ),gvn);
        if( is_dead() ) return this; // Happens cleaning out dead code
        progress = true;
      }
      // Check the incoming alias matches his parent
      if( live_stable ) {       // Liveness stable (as we are changing liveness demands)?
        int par_idx = find_alias2idx(BitsAlias.parent(alias));
        if( in(par_idx)==in(i) || // Already covered by parent?
            this==in(i) ||        // Dead loop cycle?
            // Using bulk memory, and both are dead
            (par_idx==0 && ti==TypeObj.XOBJ && t0mem.at(alias)==TypeObj.XOBJ) ) {
          remove0(i--,gvn);            // Fold into parent
          if( is_dead() ) return this; // Happens when cleaning out dead code
          progress = true;
        }
      }
    }
    if( _defs._len==1 )
      // Much pondering: MemMerge can filter liveness on slot0 (because some
      // closure goes dead so the alias for it is XOBJ).  This knowledge has
      // flowed "uphill": no one needs to provide this alias.  But also, the
      // value()s can flow downhill and the slot0 might also be XOBJ.  Then we
      // simplify to a single input edge, merging nothing.  But we cannot
      // collapse lest we "lower" liveness by making the unused alias used again.
      return live_stable ? in(0) : null; // Merging nothing
    if( progress ) return this;       // Removed some dead inputs

    // Back-to-back merges collapse
    if( mem() instanceof MemMergeNode ) {
      MemMergeNode mem = (MemMergeNode)mem();
      // Walk in reverse order, because original 'mem' aliases includes some
      // parent-defs and some child-overrides-of-parent.  If we insert the
      // parent first, it looks like the 'this' MemMerge has a stomp of the
      // parent which overrides the later walked children.
      for( int i=mem._defs._len-1; i>=1; i-- ) {
        int alias = mem.alias_at(i);
        // If alias is old, keep the original (it stomped over the incoming
        // memory).  If alias is new, use the new value.
        int idx = find_alias2idx(alias);
        if( idx == 0 )
          // Create alias slice from nearest alias parent
          create_alias_active(alias,mem.in(i),gvn);
      }
      return set_def(0,mem.in(0),gvn); // Return improved self
    }

    // Back-to-back merges not in alias#1
    for( int i=1; i<_defs._len; i++ )
      if( in(i) instanceof MemMergeNode )
        { set_def(i,((MemMergeNode)in(i)).alias2node(alias_at(i)),gvn); progress = true; }
    if( progress ) return this; // Removed back-to-back merge

    return null;
  }


  // Base memory (alias#1) comes in input#0.  Other inputs refer to other
  // aliases (see _aliases) and children follow parents (since alias#s are
  // sorted).  Each input replaces (not merges) their parent in just that
  // subtree.
  @Override public Type value(GVNGCM gvn) {
    // Base memory type in slot 0
    Type t = gvn.type(in(0));
    if( !(t instanceof TypeMem) )
      return t.above_center() ? TypeMem.EMPTY : TypeMem.FULL;
    TypeMem tm = (TypeMem)t;

    // Merge with parent.
    TypeObj[] tms = tm.alias2objs();
    int max = Math.max(tms.length,_aliases.last()+1);
    TypeObj[] tos = Arrays.copyOf(tms,max);
    for( int i=1; i<_defs._len; i++ ) {
      int alias = alias_at(i);
      Type ta = gvn.type(in(i));
      if( ta instanceof TypeMem )
        ta = ((TypeMem)ta).at(alias);
      TypeObj tao = ta instanceof TypeObj ? (TypeObj)ta
        : (ta.above_center() ? TypeObj.XOBJ : TypeObj.OBJ); // Handle ANY, ALL

      // MemMerge semantics: if it appears in a input alias, that is ALL
      // of that alias (equiv slice) and NONE appears in the default.
      // So you take it directly, and do NOT meet it.  Means I cannot
      // lose an alias into the 'default' memory, or else this optimization
      // is in-error.
      tos[alias] = tao;
      // All child aliases alive in the base type get stomped.  Aliases from
      // following inputs do not get stomped, and get set in in later iterations.
      for( int j = alias+1; j<tms.length; j++ )
        if( BitsAlias.is_parent(alias,j) )
          tos[j] = tao;
    }
    return TypeMem.make0(tos);
  }


  // Compute the liveness local contribution to def's liveness.  Ignores the
  // incoming memory types, as this is a backwards propagation of demanded
  // memory.
  @Override public TypeMem live_use( GVNGCM gvn, Node def ) {
    TypeObj[] tos = new TypeObj[_aliases.last()+1];
    tos[1] = in(0)==def ? _live.at(1) : TypeObj.XOBJ;
    for( int alias=2; alias<tos.length; alias++ ) {
      if( _aidxes.at(alias)==0 ) { // No special overrides for this alias
        // Then in-or-out according to current liveness
        tos[alias] =  _live.at(alias);
      } else {          // This alias (and all children ) overridden here
        // Either Def or some other node must supply this value
        tos[alias] = in(_aidxes.at(alias))==def ? _live.at(alias) : TypeObj.XOBJ;
      }
    }
    return TypeMem.make0(tos);
  }
  @Override public boolean basic_liveness() { return false; }

  @Override public Type all_type() { return TypeMem.FULL; }

  @Override @NotNull public MemMergeNode copy( boolean copy_edges, CallEpiNode unused, GVNGCM gvn) {
    MemMergeNode mmm = (MemMergeNode)super.copy(copy_edges, unused, gvn);
    mmm._aliases = new AryInt(_aliases._es.clone(),_aliases._len);
    mmm._aidxes  = new AryInt(_aidxes ._es.clone(),_aidxes ._len);
    return mmm;
  }
  void update_alias( Node copy, BitSet aliases, GVNGCM gvn ) {
    MemMergeNode cmem = (MemMergeNode)copy;
    assert gvn.touched(this);
    Node xobj = gvn.con(TypeObj.XOBJ);
    Type oldt = gvn.unreg(this);
    for( int i=1; i<_aliases._len; i++ ) {
      int mya = _aliases.at(i);
      if( !aliases.get(mya) ) continue; // Alias not split here
      int[] kid0_aliases = BitsAlias.get_kids(mya);
      int newalias1 = kid0_aliases[1];
      int newalias2 = kid0_aliases[2];
      cmem._update(gvn,xobj,i,mya,newalias1,newalias2);
      this._update(gvn,xobj,i,mya,newalias2,newalias1);
    }
    assert check() && cmem.check();
    gvn.rereg(this,oldt);
  }
  private void _update(GVNGCM gvn, Node xobj, int oidx, int oldalias, int newalias1, int newalias2) {
    int nidx1 = make_alias2idx(newalias1);
    set_def(nidx1,in(oidx),null); // My alias comes from the original
    int nidx2 = make_alias2idx(newalias2);
    // The other alias either comes from the default (if the original also came
    // from the default, in addition to the immediate local set), or else the
    // original never escaped and the other alias is dead.
    Node other = in(0)._live.at(oldalias)==TypeObj.XOBJ ? xobj : in(0);
    set_def(nidx2, other  ,gvn ); // The other alias comes from default
    set_def(oidx , xobj   ,gvn ); // The original goes dead
  }
  @Override public int hashCode() { return super.hashCode()+_aliases.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof MemMergeNode) ) return false;
    MemMergeNode mem = (MemMergeNode)o;
    return _aliases.equals(mem._aliases);
  }

}

