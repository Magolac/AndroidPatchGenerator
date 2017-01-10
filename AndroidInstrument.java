package fr.spirals;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import soot.Body;
import soot.BodyTransformer;
import soot.Local;
import soot.PackManager;
import soot.PatchingChain;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Transform;
import soot.Type;
import soot.Unit;

import soot.jimple.IdentityStmt;
import soot.jimple.Jimple;
import soot.jimple.RetStmt;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.options.Options;
import soot.util.Chain;


public class AndroidInstrument {
	
	private static boolean instrumented=false;
	private static final String classToInstrument="com.snowbound.pockettool.free.LevelSelector";
	private static final String methodToInstrument="onCreate";
	
	public static void main(String[] args) {		
		Options.v().set_src_prec(Options.src_prec_apk);			
		Options.v().set_output_format(Options.output_format_dex);
		Options.v().force_android_jar();		
		Options.v().set_allow_phantom_refs(true);			
       
		Scene.v().addBasicClass("java.io.PrintStream",SootClass.SIGNATURES);
        Scene.v().addBasicClass("java.lang.System",SootClass.SIGNATURES);
        
       
        PackManager.v().getPack("jtp").add(new Transform("jtp.myInstrumenter", new BodyTransformer() {
        	
			@Override
			protected void internalTransform(final Body b, String phaseName, @SuppressWarnings("rawtypes") Map options) {			
				for(SootClass sootClass:Scene.v().getClasses()){					
					if(sootClass.getName().equals(classToInstrument)){					
					
						for(SootMethod sm:sootClass.getMethods()){
							if(sm.getName().equals(methodToInstrument) && !instrumented){
								instrument(sm);	
								instrumented=true;								
						        b.validate();	
						        break;
							}
						}					
						break;						
					}
				}		
			}
		}));       
	
	soot.Main.main(args);
	}
    
      /**
	   * Takes a method as input and wraps it with a try/catch, with empty catch block
	   * @param m: method to instrument
	   */
      public static void instrument(SootMethod m) {		
		SootClass thrwCls = Scene.v().getSootClass("java.lang.Throwable");
		SootMethod mPrintStackTrace = thrwCls.getMethod("void printStackTrace(java.io.PrintStream)");
		
		SootClass clsSystem = Scene.v().getSootClass("java.lang.System");
		SootClass clsPrintStream = Scene.v().getSootClass("java.io.PrintStream");
		Type printStreamType = clsPrintStream.getType();
		SootField fldSysOut = clsSystem.getField("out", printStreamType);
		
		List<Stmt> probe = new ArrayList<Stmt>();
		Body b = m.retrieveActiveBody();
		PatchingChain<Unit> pchain = b.getUnits();			
		
		Stmt sFirstNonId = null;
		for (Iterator it = pchain.iterator(); it.hasNext(); ) {
			sFirstNonId = (Stmt) it.next();
			if (!(sFirstNonId instanceof IdentityStmt))
				break;
			}
			
			Stmt sLast = (Stmt) pchain.getLast();	
			
			for (Unit u : pchain)
				assert (!(u instanceof ReturnStmt) && !(u instanceof RetStmt)) || u == sLast;			
			
			Stmt sGotoLast = Jimple.v().newGotoStmt(sLast);
			probe.add(sGotoLast);
			
			Local lException1 = getCreateLocal(b, "<ex1>", RefType.v(thrwCls));		
						
			Stmt sCatch = Jimple.v().newIdentityStmt(lException1, Jimple.v().newCaughtExceptionRef());
			probe.add(sCatch);
						
			Local lSysOut = getCreateLocal(b, "<sysout>", printStreamType);
			Stmt sGetSysOutToLocal = Jimple.v().newAssignStmt(lSysOut, Jimple.v().newStaticFieldRef(fldSysOut.makeRef()));
			probe.add(sGetSysOutToLocal);
			Stmt sCallPrintStackTrace = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(lException1, mPrintStackTrace.makeRef(),
					lSysOut));
			probe.add(sCallPrintStackTrace);
			
			insertRightBeforeNoRedirect(pchain, probe, sLast);
			
			// Insert catch
			b.getTraps().add(Jimple.v().newTrap(thrwCls, sFirstNonId, sGotoLast, sCatch));			
    }
    
    private static Local getCreateLocal(Body b, String localName, Type t) {		
		Local l = getLocal(b, localName);
		if (l != null) {
			assert l.getType().equals(t); 
			return l;
		}
		
		Chain locals = b.getLocals();
		l = Jimple.v().newLocal(localName, t);
		locals.add(l);
		return l;
	}
    
    private static Local getLocal(Body b, String localName) {		
		Chain locals = b.getLocals();
		for (Iterator itLoc = locals.iterator(); itLoc.hasNext(); ) {
			Local l = (Local)itLoc.next();
			if (l.getName().equals(localName))
				return l;
		}		
		return null;
	}
    
    private static void insertRightBeforeNoRedirect(PatchingChain pchain, List instrumCode, Stmt s) {
		assert !(s instanceof IdentityStmt);
		for (Object stmt : instrumCode)
			pchain.insertBeforeNoRedirect((Unit) stmt, s);
	}
   
}
