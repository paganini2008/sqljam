package lazycat.series.sqljam.update;

/**
 * Batch
 * 
 * @author Fred Feng
 * @version 1.0
 */
public interface Batch extends Executor {

	Batch push(Object object);

	void flush();

	Batch setFlushSize(int flushSize);

}
