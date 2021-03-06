package edu.illinois.cs.cogcomp.lbj.coref.decoders;

import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import LBJ2.classify.Classifier;
import LBJ2.infer.GurobiHook;
import LBJ2.learn.LinearThresholdUnit;
import edu.illinois.cs.cogcomp.lbj.coref.ir.Mention;
import edu.illinois.cs.cogcomp.lbj.coref.ir.docs.Doc;
import edu.illinois.cs.cogcomp.lbj.coref.ir.examples.CExample;
import edu.illinois.cs.cogcomp.lbj.coref.ir.solutions.ChainSolution;


/** 
 * Translates classification decisions to a collection of
 * coreference equivalence classes
 * in the form of a {@code ChainSolution} via the decode method according to the
 * best link decoding algorithm.
 * The best link decoding method specifies that for each mention {@code m}
 * a link will be produced with highest scoring preceding mention {@code a}
 * only if {@link #predictedCoreferential(CExample)}
 * returns true for the example {@code doc.getCExampleFor(a, m)}.
 * Also, allows several options to be set that modify the performance
 * of the best link decoding algorithm.
 * See the relevant setter methods for details.
 * @author Eric Bengtson
 */
public class ILPDecoderBestLink
 extends ScoredCorefDecoder
 implements Serializable {
    private static final long serialVersionUID = 1L;

    
    
    /* Option variables */
    
    /** Whether to allow cataphora. */
    protected boolean m_allowCataphora = false;
    
    /** Whether to prevent long distance pronoun reference. */
    protected boolean m_preventLongDistPRO = false;
    
    /** Currently does nothing. */
    protected boolean m_experimental = false;
    
    /** Holds the optional scores log. */
    protected PrintStream m_scoresLog = null;

    
    
    /* Constructors */
    
    /** 
     * Constructor for the case where a scoring classifier
     * has had its threshold set.
     * @param scorer A scoring classifier
     * (specifically, a {@code LinearThresholdUnit}),
     * whose threshold should be set using its {@code setThreshold} method.
     * {@code scorer}'s {@code discreteValue} takes {@code CExample}s
     * and returns "true" or "false".
     * It also provides scores for the "true" value.
     */
    public ILPDecoderBestLink(LinearThresholdUnit scorer) {
    super(scorer);
    }
    
    /**
     * Constructor for use when the scoring classifier is not sufficient
     * to decide whether links should be made,
     * such as when inference is being applied.
     * Both {@code scorer} and {@code decider} must return "true" for an
     * example to be considered coreferential.
     * @param scorer Determines the score or confidence.
     * Takes {@code CExample}s and returns a score.
     * @param decider Final arbiter of linking decisions.
     * Takes {@code CExample}s and returns "true" or "false". 
     */
    public ILPDecoderBestLink(LinearThresholdUnit scorer, Classifier decider) {
    super(scorer, decider);
    }

    
    
    /* Main function */

    
    /**
     * Takes the mentions in the specified document and produces a
     * collection of coreference equivalence classes.
     * The best link decoding method specifies that for each mention {@code m}
     * a link will be produced with highest scoring preceding mention {@code a}
     * only if {@link #predictedCoreferential(CExample)}
     * returns true for the example {@code doc.getCExampleFor(a, m)}.
     * Note: Several options ignore
     * the {@link #predictedCoreferential(CExample)} method;
     * in these cases, a decider may specify false and links may still be made,
     * possibly interfering with successful inference.
     * @param doc a document whose mentions will be placed in coreference
     * classes.
     * @return A {@code ChainSolution} representing the coreference equivalence
     * classes as chains.  Links established between mentions will also
     * be given labels in the solution.  
     */
    public ChainSolution<Mention> decode(Doc doc)
    {
    if (getBooleanOption("nicknames"))
        System.err.println("Nicknames");
    setM_cacheScores(false);
    ChainSolution<Mention> sol = new ChainSolution<Mention>();

    int linkNum = 0;

    //Get examples into correct form:
    List<Mention> allMents = doc.getMentions();

    for (Mention m : allMents) {
        sol.recordExistence(m);
    }

    // Decode with ILP
    
    GurobiHook ILPsolver = new GurobiHook();
    ILPsolver.setMaximize(true);
    int [][] indexSet = new int[allMents.size()][allMents.size()];
    //int [] indexJ = new int[allMents.size() - 1];
    //double[] valueJ = new double[allMents.size() - 1];
    
    
    
//  for(int i=0; i< allMents.size()-1;++i)
    //  valueJ[i] = 1;
    //Pairwise Best link ordering:
    //Set<Mention> prosUsed = new HashSet<Mention>();
    for (int j = 1; j < allMents.size(); ++j) {
        System.out.println(j+" out of "+allMents.size());
        Mention m = allMents.get(j);
        boolean mIsPro = m.getType().equals("PRO");
        int [] indexJ = new int[j];
        double[] valueJ = new double[j];
        
        
            for (int i = j - 1; i >= 0; i--) { // a precedes m.
                Mention a = allMents.get(i);
                CExample ex = doc.getCExampleFor(a, m);
                recordStatsFor(ex);
                // add variable

                indexSet[j][i] = ILPsolver.addBooleanVariable(getTrueScore(ex));
                // add constraint
                indexJ[i] = indexSet[j][i];
                valueJ[i] = 1;

                if (a.getType().equals("PRO") && !mIsPro) {
                    ILPsolver.addEqualityConstraint(new int[] { indexSet[j][i] }, new double[] { 1.0 }, 0.0);
                }
                // System.out.print(score+" ");
            }
        ILPsolver.addLessThanConstraint(indexJ, valueJ, 1);
    }
    
    
        try {
            System.out.println("Decoding using gurobi...");
            ILPsolver.solve();
            System.out.println("Done");
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    
    for (int j = 0; j < allMents.size(); ++j) {
        Mention m = allMents.get(j);
        for (int i = j - 1; i >= 0; i--) { //a precedes m.  
        Mention bestA = allMents.get(i);

        boolean makeLink = (ILPsolver.getBooleanValue(indexSet[j][i])
                && predictedCoreferential(doc.getCExampleFor(bestA, m)));
//      if(makeLink != true && mIsPro == true)
//                      System.out.println("PRO not link!");
        if (makeLink) {
        List<String> labels = new ArrayList<String>();
        labels.add("Pairwise Best First (link #" + linkNum + ").");
            linkNum++;
        if (getBooleanOption("interactive")) {
            CExample ex = doc.getCExampleFor(bestA, m);
            labels.addAll(getEdgeLabels(ex)); //This uses the base scorer?
        }

        sol.recordEquivalence(bestA, m, labels);

        //Record linkages in mentions:
        bestA.addCorefMentsOf(m);
        m.addCorefMentsOf(bestA);
        }
        }
    } //end for m.
    System.err.println('.');
    return sol;
    } //End decode method.

    
    /* Option methods */
    
    /**
     * Specifies whether to allow pronoun cataphora
     * Specifically, if {@code allow} is true, a pronoun
     * cannot take an referent that appears after the pronoun.
     * @param allow Whether to allow a pronoun to refer to mentions
     * appearing after the pronoun.
     */
    public void setAllowPronounCataphora(boolean allow) {
    m_allowCataphora = allow;
    }

    /**
     * Specifies whether to limit pronoun reference to within
     * a small number of sentences.
     * Specifically, if {@code prevent} is true, a pronoun
     * cannot take an antecedent from any sentence earlier than the
     * previous sentence.
     * @param prevent Whether to prevent long-distance pronoun reference.
     */
    public void setPreventLongDistPRO(boolean prevent) {
    m_preventLongDistPRO = prevent;
    }

    /**
     * Processes the options by calling super
     * and calling the dedicated methods for setting specific options.
     * Also, if {@literal scoreslog} is set, constructs the {@code m_scoresLog}
     * print stream.
     * @param option The name of the option, which is generally
     * all lowercase.
     * @param value The value, which may be the string representation
     * of a boolean or real value
     * (In a format supported by by {@link Boolean#parseBoolean}
     * or {@link Double#parseDouble})
     * or any arbitrary string.
     */
    @Override
    public void processOption(String option, String value) {
    super.processOption(option, value);
    boolean bVal = Boolean.parseBoolean(value);
    if (option.equals("allowprocata")) {
        setAllowPronounCataphora(bVal);
    } else if (option.equals("allownonprotopro")) { //backwards compatible.
        setAllowPronounCataphora(bVal);
    } else if (option.equals("preventlongdistpro")) {
        setPreventLongDistPRO(bVal);
    } else if (option.equals("experimental")) {
        m_experimental = bVal;
    } else if (option.equals("allowcataphora")) {
        setAllowPronounCataphora(bVal);
    } else if (option.equals("scoreslog")) {
        try {
        m_scoresLog = new PrintStream(value);
        //TODO: Close file somewhere?
        } catch (Exception e) {
        System.err.println("Cannot open scores log file " + value);
        }
    }
    }

    
    
    /* Statistics methods */

    /**
     * Enables the recording of data about coreference examples
     * as they are used in the decoding algorithm.
     * Currently does nothing, but may be overridden or revised
     * to record any statistic.
     * This method should be called in the decode method whenever
     * an example is examined (once per examination).
     * @param ex The example whose statistics should be recorded.
     */
    protected void recordStatsFor(CExample ex) {
    /*
    boolean isPos = Boolean.parseBoolean((new coLabel()).discreteValue(ex));
    boolean apposOn = Boolean.parseBoolean(
     (new soonAppositive()).discreteValue(ex));
    boolean bothSpeakOn = Boolean.parseBoolean(
     (new bothSpeak()).discreteValue(ex));
    boolean relProOn = Boolean.parseBoolean(
     (new relativePronounFor()).discreteValue(ex));
    boolean eMatchOn = Boolean.parseBoolean(
     (new wordNetETypeMatchBetter()).discreteValue(ex));
    
    if (isPos) {
        posEx++;
        if (apposOn) apposPosEx++;
        if (bothSpeakOn) bothSpeakPosEx++;
        if (relProOn) relProPosEx++;
        if (eMatchOn) eMatchPosEx++;
    } else { //neg:
        negEx++;
        if (apposOn) apposNegEx++;
        if (bothSpeakOn) bothSpeakNegEx++;
        if (relProOn) relProNegEx++;
        if (eMatchOn) eMatchNegEx++;
    }
    */
    }

    /**
     * Enables recorded statistics to be returned.  Currently does nothing,
     * since no statistics are being recorded, but may be overridden or revised
     * to enable statistics output.
     * @return The statistics string, which is currently empty.
     */
    @Override
    public String getStatsString() {
    String s = super.getStatsString();
    return s;
    }

}