package com.cliffc.aa;

import org.junit.Assert;
import org.junit.Test;

public class TestType {
  @Test public void testType0() {
    // Trying to debug:
    //    id(+)(1,2)
    // ID needs to be a TypeFun, and have a real implementation - so that the
    // type of the incoming arg carries across to the return result.  Currently
    // return result is a SCALAR.  Needs to be "type inlined" at usages sites.
    // Type analysis current defaults to:
    //   SCALAR(1,2)
    // which blows up.
    
    // Actually can do this with type-vars, where the input type of id is the
    // same type-var as the output type.  Goes to having a Real Type-Annotation
    // language.  "id(x::a) a" parses as "a function named 'id' with one input
    // variable (named x) with a completely unconstrained type 'a', returning
    // something of the same type.".

    // So now need type-vars which U-F together.
    

    test("id(+)", Env.top().lookup("+",Type.ANY));
    test("id(+)(1,pi)",TypeFlt.make(0,64,Math.PI+1));


    
    // Simple int
    test("1",   TypeInt.TRUE);
    // Unary operator
    test("-1",  TypeInt.con( -1));
    test("!1",  TypeInt.con(  0));
    // Binary operators
    test("1+2", TypeInt.con(  3));
    test("1-2", TypeInt.con( -1));
    test("1+2*3", TypeInt.con(  7));
    // Binary with precedence check
    test(" 1+2 * 3+4 *5", TypeInt.con( 27));
    test("(1+2)*(3+4)*5", TypeInt.con(105));
    
    // Float
    test("1.2+3.4", TypeFlt.make(0,64,4.6));
    // Mixed int/float with conversion
    test("1+2.3",   TypeFlt.make(0,64,3.3));
  
    // Variable lookup
    test("pi", TypeFlt.Pi);
    // bare function lookup; returns a union of '+' functions
    test("+",  Env.top().lookup("+",Type.ANY));
    test("!",  Env.top().lookup("!",Type.ANY));
    // Function application, lispy-style WS-delimited args
    test   ("+ 1 2", TypeInt.con( 3));
    testerr("- 1 2", "\nargs:0:Either {any[-::Flt64, -::Int64]}([1]) is not a function, or is being called with 1 args but expects a different number\n- 1 2\n     ^\n");
    // Function application, traditional paren/comma args
    test   ("+(1,2)", TypeInt.con( 3));
    testerr("-(1,2)", "\nargs:0:Expected ')' but found ',' instead\n-(1,2)\n   ^\n"); // binary version
    test   ("-(1  )", TypeInt.con(-1)); // unary version
    // error; mismatch arg count
    testerr("!()"     , "\nargs:0:Either !::Int1 is not a function, or is being called with 0 args but expects a different number\n!()\n   ^\n");
    testerr("pi(1)"   , "\nargs:0:Either 3.141592653589793 is not a function, or is being called with 1 args but expects a different number\npi(1)\n     ^\n");
    testerr("+(1,2,3)", "\nargs:0:Either {any[+::Flt64, +::Int64]} is not a function, or is being called with 3 args but expects a different number\n+(1,2,3)\n        ^\n");
    // Parsed as +(1,(2*3))
    test("+ 1 2 * 3 ", TypeInt.con(7));
    // Parsed as +( (1+2*3) , (4*5+6) )
    test("+ 1 + 2 * 3 4 * 5 + 6 ", TypeInt.con(33));

    test("id"   ,Prim.ID);
    test("id(1)",TypeInt.con(1));
    test("id(+)",Env.top().lookup("+",Type.ANY));
    test("id(+)(id(1),id(pi))",TypeFlt.make(0,64,Math.PI+1));
  
  }

  static private void test( String program, Type expected ) {
    Assert.assertEquals(expected,Exec.go("args",program)._t);
  }
  static private void testerr( String program, String err ) {
    try {
      Exec.go("args",program);  // Expect to throw
      Assert.assertTrue(false); // Did not throw
    } catch( IllegalArgumentException iae ) {
      Assert.assertEquals(err,iae.getMessage());
    }
  }

  @Test public void testCommuteSymmetricAssociative() {
    // Uncomment to insert a single test to focus on
    //Assert.assertEquals(Type.SCALAR,TypePrim.ID.meet(TypeUnion.ALL_NUM));
    Assert.assertTrue(Type.check_startup());
  }  
}
