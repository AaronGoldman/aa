package com.cliffc.aa;

import org.junit.Assert;
import org.junit.Test;

public class TestType {
  @Test public void testType0() {
    // Syntax for variable assignment
    test("x=1", TypeInt.TRUE);
    test("x=y=1", TypeInt.TRUE);
    testerr("x=y=", "missing expr after assignment");
    testerr("x=y","y not defined");
    testerr("x=1+y","y not defined");
    test("x=2; y=x+1; x*y", TypeInt.con(6));
    // Re-use ref immediately after def; parses as: x=(2*3); 1+x+x*x
    test("1+(x=2*3)+x*x", TypeInt.con(43));
    testerr("x=(1+(x=2)+x)", "cannot make the same ref twice");

    
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
    testerr("+", "\nargs:0:Syntax error; trailing junk\n+\n^\n");
    test("(+)", Env.top().lookup("+",Type.ANY));
    test("(!)", Env.top().lookup("!",Type.ANY));
    // Function application, traditional paren/comma args
    test("(+)(1,2)", TypeInt.con( 3));
    test("(-)(1,2)", TypeInt.con(-1)); // binary version
    test("(-)(1  )", TypeInt.con(-1)); // unary version
    // error; mismatch arg count
    testerr("!()"     , "\nargs:0:Call to unary function !::Int1, but missing the one required argument\n!()\n ^\n");
    testerr("pi(1)"   , "\nargs:0:A function is being called, but 3.141592653589793 is not a function type\npi(1)\n     ^\n");
    testerr("(+)(1,2,3)", "\nargs:0:{any[+::Flt64, +::Int64]} does not have a 3-argument version or the argument types do not match\n(+)(1,2,3)\n          ^\n");
    // Parsed as +(1,(2*3))
    test("(+)(1, 2 * 3) ", TypeInt.con(7));
    // Parsed as +( (1+2*3) , (4*5+6) )
    test("(+)(1 + 2 * 3, 4 * 5 + 6) ", TypeInt.con(33));

    // Ok, need serious type-prop for these
    test("id"   ,Env.top().lookup("id",Type.ANY));
    test("id(1)",TypeInt.con(1));
    // TODO: Need real TypeVars for these
    //test("id((+))",Env.top().lookup("+",Type.ANY));
    //test("id(+)(id(1),id(pi))",TypeFlt.make(0,64,Math.PI+1));
  
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
