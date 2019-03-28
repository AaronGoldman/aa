package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

// CNC notes-

// Tuples are not memory, just a collection.

// TypeMem has an Alias and a Obj - either a Struct, String or Array
// (optionally Named).  Structs ARE memory and list the known fields and
// contents.

// NewNode produces both a memory and a pointer.

// Example:  x = @{ a:=1, b:='abc'};
// 'x' is a pointer-to-mem-alias-X.
// Mem-alias X is exactly @{ a:=1, b:='abc' };
// Example: x.a
//   Load(X,x,.a) -- Mem-alias X, ptr 'x', field '.a'.  This is c-fold to a '1'.
// Example: foo(); x.a;
//   Here memory is merged & aliased, so the mem-alias from foo() is wide.
//   Load(WIDE,x,.a) -- wide-alias can split out a 'widened' version of mem-alias-X
//   This has value type of 'real' (since '1' can widen to either int or float).
// NO - use the value of mem-alias-X from the Call return.  Calls merge memory at
//   function head, and produce it out.  Assume foo() updates mem-alias-X.
// Example: x.b:='def'
//   This updates mem-alias-X from @{a:=2,b:='abc'} to @{a:=2,b:='def'}

// Alias#s merge all allocations from the same allocation site, but then are
// never merged after that - no "wide" or "fat" memory approximations.  Might
// happen strictly for representation compression, but always without loss.

// Function signatures include memory - untouched memory, read-only memory, r/w
// memory, freed vs borrowed memory, etc.  The default fcn sig will be to use
// and return changed All memory.  'Pure' functions do not read or write
// memory, and we also can support read-only functions, or functions which only
// operate on certain aliases (those passed-in).

// TypeMem maps from Alias# to a TypeObj, and generically TypeObj for bulk
// (non-aliased).  Not allowed to Load/Store from Bulk memory (approx issues).
// TypeMem supports a 'ld' for doing approximate base-pointer meet, and a
// NewNode+TypeMem merge (plus the usual Meet for PhiNodes).
//
// Action items:
//
// - An assert that walks the entire graph, and ensures no duplicate memory
//   aliases in any program slice.
// - StartNode produces a empty TypeMem.
// - TypeMem supports merging TypeMems with unrelated aliases.  Error to merge
//   same alias.
// - Calls take in a TypeMem and can update all Aliases in the TypeMem's
//   subtypes.  Calls can be more strongly user-typed to limit r/w aliases -
//   but in any case the gvn.opto will discover the minimal update set.
// - Phi keeps mem types fully alias precise unless TypePtrs are also becoming
//   imprecise?  No need to ever get approx?



// Merging 'wide' memory (memory from all prior state) and a new 'skinny'
// memory from a NewNode.
public class MergeMemNode extends Node {
  public MergeMemNode( Node wide, Node skinny ) { super(OP_MERGE,wide,skinny); }
  
  @Override public Node ideal(GVNGCM gvn) {
    // If the skinny memory is from a MProj from a NewNode, and the only proj
    // is the MProj, then there is no *address* user, and the New object must
    // be dead.  Remove the New.
    //if( in(1) instanceof MProjNode &&
    //    in(1).in(0) instanceof NewNode &&
    //    in(1).in(0)._uses._len==1 )
    //  return in(0);             // Skinny memory is dead, nothing to merge
    return null;
  }

  @Override public Type value(GVNGCM gvn) {
    Type twide = gvn.type(in(0));
    Type tskin = gvn.type(in(1));
    if( !(twide instanceof TypeMem) ||
        !(tskin instanceof TypeMem) )
      return twide.above_center() ? TypeMem.MEM.dual() : TypeMem.MEM;
    TypeMem tmerge = ((TypeMem)twide).merge((TypeMem)tskin);
    return tmerge;
  }
  @Override public Type all_type() { return TypeMem.MEM; }
}

