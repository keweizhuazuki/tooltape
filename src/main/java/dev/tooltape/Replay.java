package dev.tooltape;

import java.util.*;

/** Offline stub. No delegate exists, so this boundary cannot execute a real tool. */
public final class Replay implements Tool, AutoCloseable {
    private final Tape tape; private final CapturePolicy policy; private int position; private boolean failed;
    public Replay(Tape tape, CapturePolicy policy) {this.tape=Objects.requireNonNull(tape);this.policy=Objects.requireNonNull(policy);}
    public static final class Divergence extends AssertionError {private static final long serialVersionUID=1L; public Divergence(String message){super(message);}}
    public static final class RecordedToolException extends Exception {
        private static final long serialVersionUID=1L;
        private final String originalType;
        RecordedToolException(String type){super("Recorded tool failure: "+type);originalType=type;}
        public String originalType(){return originalType;}
    }
    @Override public synchronized Map<String,Object> call(String name,Map<String,Object> arguments) throws RecordedToolException {
        if(failed)throw new Divergence("Replay already diverged at event "+position);
        if(position>=tape.events().size())throw mismatch("unexpected extra call "+name);
        Tape.Event expected=tape.events().get(position);String actual=Json.write(policy.input(arguments));
        if(!expected.tool().equals(name))throw mismatch("tool: expected "+expected.tool()+", actual "+name);
        if(!expected.input().equals(actual))throw mismatch("arguments\nexpected: "+expected.input()+"\nactual:   "+actual);
        position++;
        if(expected.status().equals("ERROR"))throw new RecordedToolException(expected.result());
        return Json.object(expected.result());
    }
    private Divergence mismatch(String detail){failed=true;return new Divergence("First divergence at event "+position+": "+detail);}
    public synchronized void assertComplete(){if(failed)throw new Divergence("Replay already diverged at event "+position);if(position!=tape.events().size())throw mismatch("missing "+(tape.events().size()-position)+" call(s)");}
    public int consumed(){return position;}
    @Override public void close(){assertComplete();}
}
