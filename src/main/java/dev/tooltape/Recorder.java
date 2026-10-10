package dev.tooltape;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates a new tape, flushing each completed serial call. Never overwrites a fixture. */
public final class Recorder implements Tool, AutoCloseable {
    private final BufferedWriter writer; private final Tool delegate; private final CapturePolicy policy;
    private final AtomicBoolean busy=new AtomicBoolean(); private int index; private boolean closed, poisoned; private int bytes=Tape.HEADER.length()+1;
    public Recorder(Path path, Tool delegate, CapturePolicy policy) throws IOException {
        this.delegate=Objects.requireNonNull(delegate);this.policy=Objects.requireNonNull(policy);
        writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        writer.write(Tape.HEADER+"\n");writer.flush();
    }
    @Override public Map<String,Object> call(String name, Map<String,Object> arguments) throws Exception {
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("Concurrent or recursive calls are unsupported");
        try {
            if(closed||poisoned)throw new IllegalStateException("Recorder is closed or failed");
            Tape.validateName(name);String input=Json.write(policy.input(arguments)); long start=System.nanoTime();
            Map<String,Object> result;
            try {result=delegate.call(name,arguments);}catch(Exception original){
                try {append(new Tape.Event(index,System.nanoTime()-start,name,input,"ERROR",original.getClass().getName()));}catch(Exception writeFailure){poisoned=true;original.addSuppressed(writeFailure);}throw original;
            }
            try {append(new Tape.Event(index,System.nanoTime()-start,name,input,"OK",Json.write(policy.output(result))));}catch(Exception failure){poisoned=true;throw failure;}
            return result;
        }finally{busy.set(false);}
    }
    private void append(Tape.Event event) throws IOException {String line=event.line()+"\n";int size=line.getBytes(StandardCharsets.UTF_8).length;if((long)bytes+size>Tape.MAX_BYTES)throw new IOException("Tape exceeds 16 MiB; recording stopped after tool execution");writer.write(line);writer.flush();bytes+=size;index++;}
    @Override public void close() throws IOException {if(!busy.compareAndSet(false,true))throw new IllegalStateException("Cannot close during a call");try{closed=true;writer.close();}finally{busy.set(false);}}
}
