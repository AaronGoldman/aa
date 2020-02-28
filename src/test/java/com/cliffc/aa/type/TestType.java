package com.cliffc.aa.type;

import com.cliffc.aa.node.NewNode;
import com.cliffc.aa.node.PrimNode;
import com.cliffc.aa.util.Ary;
import org.junit.Test;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class TestType {
  // temp/junk holder for "instant" junits, when debugged moved into other tests
  @Test public void testType() {
    Type.init0(new HashMap<>());
    // Both high, but falling hard, so names have to fall hard too
    Type t0 = TypeStruct.NAMEPT.dual();
    Type t1 = TypeStr.STR.dual();
    Type mt = t1.meet(t0);
    assertEquals(TypeObj.OBJ,mt);
  }

  @Test public void testNamesInts() {
    Type.init0(new HashMap<>());

    // Lattice around int8 and 0 is well formed; exactly 3 edges, 3 nodes
    // Confirm lattice: {~i16 -> ~i8 -> 0 -> i8 -> i16 }
    // Confirm lattice: {        ~i8 -> 1 -> i8        }
    Type  i16= TypeInt.INT16;
    Type  i8 = TypeInt.INT8;
    Type xi8 = i8.dual();
    Type xi16= i16.dual();
    Type z   = TypeInt.FALSE;
    Type o   = TypeInt.TRUE;
    assertEquals(xi8,xi8.meet(xi16)); // ~i16-> ~i8
    assertEquals( z ,z  .meet(xi8 )); // ~i8 ->  0
    assertEquals(i8 ,i8 .meet(z   )); //  0  -> i8
    assertEquals(i16,i16.meet(xi8 )); //  i8 -> i16
    assertEquals( o ,o  .meet(xi8 )); // ~i8 ->  1
    assertEquals(i8 ,i8 .meet(o   )); //  1  -> i8

    // Lattice around n:int8 and n:0 is well formed; exactly 2 edges, 3 nodes
    // Confirm lattice: {N:~i8 -> N:1 -> N:i8}
    // Confirm lattice: {N:~i8 -> N:0 -> N:i8}
    Type ni8 = TypeInt.INT8.set_name("__test_enum:");
    Type xni8= ni8.dual();      // dual name:int8
    Type no  = o.set_name("__test_enum:");
    Type nz  = z.set_name("__test_enum:");
    assertEquals(no ,no .meet(xni8)); // N:~i8 -> N: 1
    assertEquals(ni8,ni8.meet(no  )); // N:  1 -> N:i8
    assertEquals(nz ,nz .meet(xni8)); // N:~i8 -> N:0
    assertEquals(ni8,ni8.meet(nz  )); //   N:0 -> N:i8
    assertEquals(no ,no .meet(xni8)); // n:1 & n:~i8 -> mixing 0 and 1

    // Crossing lattice between named and unnamed ints
    //      Confirm lattice: {~i8 -> N:~i8 -> 0 -> N:i8 -> i8; N:0 -> 0 }
    // NOT: Confirm lattice: {N:~i8 -> ~i8; N:i8 -> i8 }
    assertEquals(xni8,xni8.meet( xi8));//   ~i8 -> N:~i8
    assertEquals(   z, z  .meet(xni8));// N:~i8 -> {0,1}??? When falling off from a Named Int, must fall below ANY constant to keep a true lattice
    assertEquals(  i8, ni8.meet(   z));//     0 -> N:i8
    assertEquals(  i8,  i8.meet( ni8));// N: i8 ->   i8
    assertEquals(   z,   z.meet(  nz));// N:  0 ->    0

    assertEquals(xni8,xi8.meet(xni8)); // N:~i8 <- ~i8
    assertEquals(o, o .meet(xni8)); // 1 & N:~i8
  }

  // Memory is on a different line than pointers.
  // Memory canNOT be nil, but ptrs to memory can be nil.
  // Memory contents can be any/all, vs ptrs being any/all.
  // A any-ptr-alias#6 means *any* of the alias#6 choices; same for all.
  // all ->  mem  -> { str,tup} -> { string constants, tuple constants} -> {~str,~tup} -> ~mem    -> any
  // all -> *mem? -> {*mem,nil} -> {*str,*tup,nil} -> {~*str,~*tup,nil} -> {~*mem,nil} -> ~*mem+? -> any
  @Test public void testOOPsNulls() {
    Type.init0(new HashMap<>());
    Type bot = Type      .ALL;

    Type mem = TypeMem   .MEM;  // All memory
    Type str = TypeStr   .STR;  // All Strings
    Type tup = TypeStruct.ALLSTRUCT; // All Structs

    Type abc = TypeStr  .ABC;   // String constant
    Type zer = TypeInt  .FALSE;
    Type tp0 = TypeStruct.make(zer);  // tuple of a '0'

    Type tupX= tup.dual();
    Type strX= str.dual();
    Type memX= mem.dual();

    Type top = Type.ANY;

    assertTrue( top.isa(memX));

    //assertTrue(memX.isa(strX)); // mem is a CONTAINER for memory objects, e.g. Struct,Str
    //assertTrue(memX.isa(tupX));

    assertTrue( strX.isa(abc));
    assertTrue( tupX.isa(tp0));
    assertTrue(!strX.isa(zer));
    assertTrue(!tupX.isa(zer));

    assertTrue( abc .isa(str));
    assertTrue( tp0 .isa(tup));
    assertTrue(!zer .isa(str));
    assertTrue(!zer .isa(tup));

    //assertTrue( str .isa(mem)); // mem is a CONTAINER for memory objects, e.g. Struct,Str
    //assertTrue( tup .isa(mem));

    assertTrue( mem .isa(bot));

    // ---
    Type pmem0= TypeMemPtr.OOP0;    // *[ALL]?
    Type pmem = TypeMemPtr.OOP ;    // *[ALL]
    Type pstr0= TypeMemPtr.STR0;    // *[str]?
    TypeMemPtr pstr = TypeMemPtr.STRPTR; // *[str]
    Type ptup0= TypeMemPtr.STRUCT0; // *[tup]?
    Type ptup = TypeMemPtr.STRUCT;  // *[tup]

    Type pabc0= TypeMemPtr.ABC0;    // *["abc"]?
    TypeMemPtr pabc = TypeMemPtr.ABCPTR; // *["abc"]
    TypeMemPtr pzer = TypeMemPtr.make(BitsAlias.type_alias(BitsAlias.RECORD),TypeStruct.ALLSTRUCT);// *[(0)]
    Type pzer0= pzer.meet_nil();  // *[(0)]?
    Type nil  = Type.NIL;

    Type xtup = ptup .dual();
    Type xtup0= ptup0.dual();
    TypeMemPtr xstr = pstr.dual();
    Type xstr0= pstr0.dual();
    Type xmem = pmem .dual();
    Type xmem0= pmem0.dual();

    assertTrue( top .isa(xmem0));
    assertTrue(xmem0.isa(xmem ));

    assertTrue(xmem0.isa(xstr0));
    assertTrue(xmem .isa(xstr ));
    assertTrue(xmem0.isa(xtup0));
    assertTrue(xmem .isa(xtup ));

    // "~str?" or "*[~0+4+]~str?" includes a nil, but nothing can fall to a nil
    // (breaks lattice)... instead they fall to their appropriate nil-type.
    assertEquals(TypeMemPtr.NIL,xstr0.meet( nil ));

    // This is a choice ptr-to-alias#1, vs a nil-able ptr-to-alias#2.  Since
    // they are from different alias classes, they are NEVER equal (unless both
    // nil).  We cannot tell what they point-to, so we do not know if the
    // memory pointed-at is compatible or not.
    assertTrue (xstr0.isa(pabc0)); // ~*[1]+0 vs ~*[2]?
    assertTrue (xstr .isa(pabc ));
    // We can instead assert that values loaded are compatible:
    assertTrue (TypeMem.MEM.dual().ld(xstr).isa(TypeMem.MEM_ABC.ld(pabc)));

    // "~@{}?" or "*[~0+2+]~@{}?" includes a nil, but nothing can fall to a nil
    // (breaks lattice)... instead they fall to their appropriate nil-type.
    assertEquals(TypeMemPtr.NIL,xtup0.meet( nil ));
    assertTrue (xtup0.isa(pzer0));
    assertTrue (xtup .isa(pzer ));
    //assertTrue(TypeMem.MEM_TUP.dual().ld(xstr).isa(TypeMem.MEM_ZER.ld(pabc)));

    assertTrue ( nil .isa(pabc0));
    assertTrue ( nil .isa(pzer0));

    assertTrue ( nil .isa(pstr0));
    assertTrue (pabc0.isa(pstr0));
    assertTrue (pabc .isa(pstr ));
    assertTrue (TypeMem.MEM_ABC.ld(pabc).isa(TypeMem.MEM.ld(pstr)));
    assertTrue ( nil .isa(ptup0));
    assertTrue (pzer0.isa(ptup0));
    assertTrue (pzer .isa(ptup ));
    //assertTrue(TypeMem.MEM_TUP.dual().ld(xstr).isa(TypeMem.MEM_ZER.ld(pabc)));
    assertTrue (ptup0.isa(pmem0));
    assertTrue (ptup .isa(pmem ));

    assertTrue (pmem .isa(pmem0));
    assertTrue (pmem0.isa( bot ));


    // Crossing ints and *[ALL]+null
    Type  i8 = TypeInt.INT8;
    Type xi8 = i8.dual();
    assertEquals( Type.NSCALR, xi8.meet(xmem0)); // ~OOP+0 & ~i8 -> 0
  }

  @Test public void testStructTuple() {
    Type.init0(new HashMap<>());
    Type nil  = Type.NIL;
    // Tuple is more general that Struct
    Type tf = TypeStruct.TFLT64; //  (  flt64); choice leading field name
    Type tsx= TypeStruct. FLT64; // @{x:flt64}; fixed  leading field name
    Type tff = tsx.meet(tf);     //
    assertEquals(tf,tff);        // tsx.isa(tf)
    TypeStruct t0 = TypeStruct.make(nil); //  (nil)
    TypeStruct ts0= TypeStruct.make(new String[]{"x"},TypeStruct.ts(nil));  // @{x:nil}
    Type tss = ts0.meet(t0);
    assertEquals(t0,tss);      // t0.isa(ts0)
    byte[] finals = new byte[]{TypeStruct.FFNL};

    // meet @{c:0}? and @{c:@{x:1}?,}
    int alias0 = BitsAlias.type_alias(BitsAlias.RECORD);
    int alias1 = BitsAlias.type_alias(alias0);
    int alias2 = BitsAlias.type_alias(BitsAlias.RECORD);
    int alias3 = BitsAlias.type_alias(alias0);
    TypeObj a1 = TypeStruct.make(new String[]{"c"},TypeStruct.ts(Type.NIL                   ),finals); // @{c:nil}
    TypeObj a3 = TypeStruct.make(new String[]{"x"},TypeStruct.ts(TypeInt.TRUE               ),finals); // @{x: 1 }
    TypeObj a2 = TypeStruct.make(new String[]{"c"},TypeStruct.ts(TypeMemPtr.make_nil(alias3,a3)),finals); // @{c:*{3#}?}
    Ary<TypeObj> tos = new Ary<>(TypeObj.class);
    tos.setX(BitsAlias.ALL,TypeObj.OBJ);
    tos.setX(alias1,a1);
    tos.setX(alias2,a2);
    tos.setX(alias3,a3);
    TypeMem mem = TypeMem.make0(tos.asAry());
    // *[1]? join *[2] ==> *[1+2]?
    Type ptr12 = Type.NIL.join(TypeMemPtr.make(-alias1,a1)).join( TypeMemPtr.make(-alias2,a2));
    // mem.ld(*[1+2]?) ==> @{c:0}
    Type ld = mem.ld((TypeMemPtr)ptr12);
    TypeObj ax = TypeStruct.make(new String[]{"c"},TypeStruct.ts(Type.NIL),finals ); // @{c:nil}
    assertEquals(ax,ld);
  }

  // meet of functions: arguments *join*, fidxes union (meet), and return types
  // meet.  Inverse of all of this for functions join'ing, and UnresolvedNode
  // is a function join.
  @Test public void testFunction() {
    Type.init0(new HashMap<>());
    Type ignore = TypeTuple.ANY; // Break class-loader cycle; load Tuple before Fun.
    PrimNode[] ignore2 = PrimNode.PRIMS; // Force node

    TypeFunPtr gf = TypeFunPtr.GENERIC_FUNPTR;
    // New functions fall squarely between +/- GENERIC_FUNPTR.

    // TypeTuple structure demands the shortest Tuple wins the "length
    // war" (determines the length of the result based on short's any/all flag).
    TypeFunPtr f1i2i = TypeFunPtr.make_new(TypeStruct.INT64_INT64,TypeInt.INT64);
    // To be a GF result, GF has to be shorter and high; the isa does a meet of
    // TypeFunPtrs which does a *join* of args, which duals the GF args down
    // low.  GF is zero length and low, and wins the meet.
    assertTrue(f1i2i.isa(gf));        // To be long  result, short must be high
    // To have GF.dual() be anything else and short, GF.dual must be high and
    // thus the result is a copy of F1I2I.
    assertTrue(gf.dual().isa(f1i2i)); // To be short result, short must be low

    assertTrue(f1i2i.isa(gf));
    TypeFunPtr f1f2f = TypeFunPtr.make_new(TypeStruct.FLT64_FLT64,TypeFlt.FLT64);
    assertTrue(f1f2f.isa(gf));
    TypeFunPtr mt = (TypeFunPtr)f1i2i.meet(f1f2f);
    int fidx0 = f1i2i.fidx();
    int fidx1 = f1f2f.fidx();
    BitsFun funs = BitsFun.make0(fidx0).meet(BitsFun.make0(fidx1));
    TypeFunPtr f3i2r = TypeFunPtr.make(funs,TypeStruct.make(TypeStruct.ARGS_XY,TypeStruct.ts(Type.NIL,Type.REAL,Type.REAL)),Type.REAL);
    assertEquals(f3i2r,mt);
    assertTrue(f3i2r.isa(gf));
    assertTrue(f1i2i.isa(f3i2r));
    assertTrue(f1f2f.isa(f3i2r));

    TypeFunPtr f2 = TypeFunPtr.make(BitsFun.make0(fidx1),TypeStruct.INT64_INT64,TypeInt.INT64); // Some generic function (happens to be #23, '&')
    assertTrue(f2.isa(gf));
  }

  // Test limits on recursive type structures; recursively building nested
  // structures caps out in the type system at some reasonable limit.
  @Test public void testRecursive() {
    Type.init0(new HashMap<>());
    String[] flds = new String[]{"n","v"};

    // Recursive types no longer cyclic in the concrete definition?  Because
    // TypeObj can contain TypeMemPtrs but not another nested TypeObj...
    final int alias1 = BitsAlias.new_alias(BitsAlias.RECORD);
    final TypeMemPtr ts0ptr = TypeMemPtr.make    (alias1,TypeStruct.ALLSTRUCT);
    final TypeMemPtr ts0ptr0= TypeMemPtr.make_nil(alias1,TypeStruct.ALLSTRUCT);

    // Anonymous recursive structs -
    // - struct with pointer to self
    byte[] finals = TypeStruct.ffnls(2);
    TypeStruct ts0 = TypeStruct.malloc("",false,flds,TypeStruct.ts(2),finals);
    ts0._hash = ts0.compute_hash();
    ts0._ts[0] = ts0ptr;    ts0._cyclic = true;
    ts0._ts[1] = TypeInt.INT64;
    ts0 = ts0.install_cyclic(ts0.reachable());
    TypeMem ts0mem = TypeMem.make(alias1,ts0); // {1:@{n:*[1],v:int} }

    // - struct with pointer to self or nil
    TypeStruct ts1 = TypeStruct.malloc("",false,flds,TypeStruct.ts(2),finals);
    ts1._hash = ts1.compute_hash();
    ts1._ts[0] = ts0ptr0;  ts1._cyclic = true;
    ts1._ts[1] = TypeInt.INT64;
    ts1 = ts1.install_cyclic(ts1.reachable());
    TypeMem ts1mem = TypeMem.make(alias1,ts1); // {1:@{n:*[0,1],v:int} }

    Type tsmt = ts0.meet(ts1);
    assertEquals(ts1,tsmt);
    Type tsmemmt = ts0mem.meet(ts1mem);
    assertEquals(ts1mem,tsmemmt);

    // Cyclic named struct: Memory#2 :A:@{n:*[0,2],v:int}
    // If we unrolled this (and used S for Struct and 0 for Nil) we'd get:
    // AS0AS0AS0AS0AS0AS0...
    final int alias2 = BitsAlias.new_alias(BitsAlias.RECORD);
    TypeMemPtr tptr2= TypeMemPtr.make_nil(alias2,TypeObj.OBJ); // *[0,2]
    TypeStruct ts2 = TypeStruct.make(flds,TypeStruct.ts(tptr2,TypeInt.INT64)); // @{n:*[0,2],v:int}
    TypeStruct ta2 = ts2.set_name("A:");

    // Peel A once without the nil: Memory#3: A:@{n:*[2],v:int}
    // ASAS0AS0AS0AS0AS0AS0...
    final int alias3 = BitsAlias.new_alias(BitsAlias.RECORD);
    TypeMemPtr tptr3= TypeMemPtr.make(alias3,TypeObj.OBJ); // *[3]
    TypeStruct ts3 = TypeStruct.make(flds,TypeStruct.ts(tptr2,TypeInt.INT64)); // @{n:*[2],v:int}
    TypeStruct ta3 = ts3.set_name("A:");

    // Peel A twice without the nil: Memory#4: A:@{n:*[3],v:int}
    // ASASAS0AS0AS0AS0AS0AS0...
    final int alias4 = BitsAlias.new_alias(BitsAlias.RECORD);
    TypeStruct ts4 = TypeStruct.make(flds,TypeStruct.ts(tptr3,TypeInt.INT64)); // @{n:*[3],v:int}
    TypeStruct ta4 = ts4.set_name("A:");

    // Then make a MemPtr{3,4}, and ld - should be a PeelOnce
    // Starting with the Struct not the A we get:
    // Once:  SAS0AS0AS0AS0AS0AS0...
    // Twice: SAS AS0AS0AS0AS0AS0...
    // Meet:  SAS0AS0AS0AS0AS0AS0...
    // which is the Once yet again
    TypeObj[] tos = new TypeObj[alias4+1];
    tos[0]=null;
    tos[1]=TypeObj.XOBJ;
    tos[alias2]=ta2;
    tos[alias3]=ta3;
    tos[alias4]=ta4;
    TypeMem mem234 = TypeMem.make0(tos);
    TypeMemPtr ptr34 = (TypeMemPtr)TypeMemPtr.make(alias3,TypeObj.OBJ).meet(TypeMemPtr.make(alias4,TypeObj.OBJ));

    // Since hacking ptrs about from mem values, no cycles so instead...
    Type mta = mem234.ld(ptr34);
    //assertEquals(ta3,mta);
    TypeMemPtr ptr023 = (TypeMemPtr)TypeMemPtr.make_nil(alias2,TypeObj.OBJ).meet(TypeMemPtr.make(alias3,TypeObj.OBJ));
    TypeStruct xts = TypeStruct.make(flds,TypeStruct.ts(ptr023,TypeInt.INT64));
    Type xta = xts.set_name("A:");
    assertEquals(xta,mta);

    // Mismatched Names in a cycle; force a new cyclic type to appear
    final int alias5 = BitsAlias.new_alias(BitsAlias.RECORD);
    TypeStruct tsnb = TypeStruct.make(flds,TypeStruct.ts(TypeMemPtr.make_nil(alias5,TypeObj.OBJ),TypeFlt.FLT64));
    TypeStruct tfb = tsnb.set_name("B:");
    Type mtab = ta2.meet(tfb);

    // TODO: Needs a way to easily test simple recursive types
    TypeStruct mtab0 = (TypeStruct)mtab;
    assertEquals("n",mtab0._flds[0]);
    assertEquals("v",mtab0._flds[1]);
    TypeMemPtr mtab1 = (TypeMemPtr)mtab0.at(0);
    assertTrue(mtab1._aliases.test(alias2)&& mtab1._aliases.test(alias5));
    assertEquals(Type.REAL,mtab0.at(1));


    // In the ptr/mem model, all Objs from the same NewNode are immediately
    // approximated by a single Alias#.  This stops any looping type growth.
    // The only way to get precision back is to inline the NewNode and get new
    // Alias#s.

    // Nest a linked-list style tuple 10 deep; verify actual depth is capped at
    // less than 5.  Any data loop must contain a Phi; if structures are
    // nesting infinitely deep, then it must contain a NewNode also.
    int alias = BitsAlias.new_alias(BitsAlias.RECORD);
    Type[] tts = TypeStruct.ts(Type.NIL,TypeInt.con(0));
    TypeStruct ts = TypeStruct.make(TypeStruct.FLDS(2),tts,finals);
    TypeMemPtr phi = TypeMemPtr.make(alias,ts);
    for( int i=1; i<20; i++ ) {
      Type[] ntts = TypeStruct.ts(phi,TypeInt.con(i));
      TypeStruct newt = TypeStruct.make(TypeStruct.FLDS(2),ntts,finals);
      TypeStruct approx = newt.approx(NewNode.CUTOFF,alias);
      phi = TypeMemPtr.make(alias,approx);
    }
    HashMap<Type,Integer> ds = phi.depth();
    int d = TypeMemPtr.max(alias,ds);
    assertTrue(0 <= d && d <10);
  }

  // Test a cycle with two names on mismatched cycle boundaries
  @Test public void testNameCycle() {
    Type.init0(new HashMap<>());
    Object dummy0 = TypeStruct.TYPES;
    Object dummy1 = TypeMemPtr.TYPES;
    // Make a cycle: 0_A: -> 1_(n=*,v=i64) -> 2_TMP -> 3_B: -> 4_(n=*,v=f64) -> 5_TMP ->
    // Dual; then meet ~4_() and ~0_A
    String[] flds = new String[]{"n","v"};
    byte[] finals = TypeStruct.ffnls(2);
    final int alias = BitsAlias.RECORD;

    Type.RECURSIVE_MEET++;
    TypeStruct as1 = TypeStruct.malloc("",false,flds,TypeStruct.ts(2),finals).set_name("A:");
    TypeStruct bs4 = TypeStruct.malloc("",false,flds,TypeStruct.ts(2),finals).set_name("B:");
    as1._hash = as1.compute_hash();  as1._cyclic = true;
    bs4._hash = bs4.compute_hash();  bs4._cyclic = true;
    TypeMemPtr ap5 = TypeMemPtr.make(alias,as1);  ap5._cyclic = true;
    TypeMemPtr bp2 = TypeMemPtr.make(alias,bs4);  bp2._cyclic = true;
    as1._ts[0] = bp2;
    as1._ts[1] = TypeInt.INT64;
    bs4._ts[0] = ap5;
    bs4._ts[1] = TypeFlt.FLT64;
    Type.RECURSIVE_MEET--;
    as1 = as1.install_cyclic(as1.reachable());
    bp2 = (TypeMemPtr)as1._ts[0];
    bs4 = (TypeStruct)bp2._obj;
    ap5 = (TypeMemPtr)bs4._ts[0];

    Type das1 = as1.dual();     // ~A:@{b,int}
    Type dbs4 = bs4.dual();     // ~B:@{a,flt}
    // Since names mismatch, but both as1 and bs4 are high... must fall hard.
    Type mt = das1.meet(dbs4);  // ~ ~@{a join b, int join flt} ==> @{a join b, int32}
    TypeStruct smt = (TypeStruct)mt;
    assertEquals(TypeInt.INT32,smt._ts[1]);
    TypeMemPtr smp = (TypeMemPtr)smt._ts[0];
    assertEquals(smt,smp._obj);
    assertEquals(BitsAlias.RECORD_BITS,smp._aliases);

    Type mx = as1.dual().meet(dbs4);
    assertEquals(smt,mx);
  }


  @Test public void testCommuteSymmetricAssociative() {
    Type.init0(new HashMap<>());
    BitsFun.make_new_fidx(BitsFun.ALL);

    assertTrue(Type.check_startup());
  }
}
