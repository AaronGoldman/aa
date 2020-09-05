package com.cliffc.aa;

import com.cliffc.aa.util.SB;
import org.junit.*;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;

import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class TestREPL {
  // Replace STDIN/STDOUT and track them
  @Rule public final SystemOutRule sysOut = new SystemOutRule().enableLog().muteForSuccessfulTests();
  @Rule public final SystemErrRule sysErr = new SystemErrRule().enableLog().muteForSuccessfulTests();

  private Scanner _stdin;
  private String _prog;
  @Before public void open_repl() {
    _stdin = REPL.init();
    _prog = "";
    // Drain the initial prompt string so tests do not expect one
    String actual = sysOut.getLog();
    String expected = REPL.prompt;
    assertEquals(expected,actual);
    sysOut.clearLog();
    assertTrue(sysErr.getLog().isEmpty());
  }

  @After public void close_repl() {
  }

  // Does the REPL test work?
  @Test public void testREPL00() {
    test("2", "2");
  }

  // Basic REPL, with errors & recovery
  @Test public void testREPL01() {
    test("2+3", "5");
    test("x=3", "3");
    test("x*x", "9");
    testerr("y*y", "y*y", "Unknown ref 'y'",6,0);
    test("x=4", "4");
    testerr("x+x", "x=4", "Cannot re-assign final val 'x'",6,0);
    test("3+2", "5");
  }

  // Requires
  @Test public void testREPL02() {
    test("do={pred->{body->pred()?body():^; do pred body}}","do={pred -> }");
    test("sum:=0; i:=0; do {i++ < 100} {sum:=sum+i}; sum","int64");
  }

  // Jam the code into STDIN, run the REPL one-step, read the STDOUT and compare.
  private void test( String partial, String expected ) {
    _prog = REPL.go_one(_prog,partial);
    String actual = sysOut.getLog();
    String exp = expected+System.lineSeparator()+REPL.prompt;
    assertEquals(exp,actual);
    sysOut.clearLog();
    assertTrue(sysErr.getLog().isEmpty());
  }
  // Jam the code into STDIN, run the REPL one-step, read the STDOUT and compare.
  // Includes the lengthy error message.
  private void testerr( String partial, String parerr, String expected, int line, int cur_off ) {
    _prog = REPL.go_one(_prog,partial);
    String actual = sysOut.getLog();
    String cursor = new String(new char[cur_off]).replace('\0', ' ');
    String exp = new SB().p("stdin:").p(line).p(":").p(expected).nl().p(parerr).p(';').nl().p(cursor).p('^').nl().p(REPL.prompt).toString();
    assertEquals(exp,actual);
    sysOut.clearLog();
    assertTrue(sysErr.getLog().isEmpty());
  }
}
