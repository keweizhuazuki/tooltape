package dev.tooltape;
import java.util.Map;
/** Serial application boundary. Do not perform side effects outside this boundary during replay. */
@FunctionalInterface
public interface Tool { Map<String,Object> call(String name, Map<String,Object> arguments) throws Exception; }
