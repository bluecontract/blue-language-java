package blue.coordination.processor;

import blue.coordination.sdk.SourceOrder;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.repo.coordination.TimelineChannel;
import java.math.BigInteger;
import java.util.*;

/** Runs the unchanged shipped channel-newness and SDK ordering implementations.
 * The processor order subjects are test inputs at the internal channel boundary;
 * they are NOT proposed authored Blue checkpoint wire values.
 */
public final class CheckpointRuntimeProbe {
  private static int count;
  private static final List<String> names = new ArrayList<>();
  private static void test(String name, boolean truth) {
    if (!truth) throw new AssertionError(name);
    names.add(name); count++;
  }
  private static Node subject(long t, String event) {
    return new Node().properties("semantics",new Node().value(
        TimelineExternalSubscriptionFunctions.TIMELINE_ORDER_SUBJECT_VERSION))
      .properties("timestamp",new Node().value(BigInteger.valueOf(t)))
      .properties("timelineBlueId",new Node().value("test-timeline"))
      .properties("entryBlueId",new Node().value(event));
  }
  private static ChannelCheckpointContext context(String scope,String key,long eventTime,Long previousTime) {
    String current="e"+eventTime, previous=previousTime==null?null:"e"+previousTime;
    return ChannelCheckpointContext.of(scope,key,new Node().value("raw-event"),current,
        subject(eventTime,current), previousTime==null?null:subject(previousTime,previous),
        previous,Collections.emptyMap());
  }
  private static boolean newer(ChannelCheckpointContext c) {
    return new TimelineChannelProcessor().isNewerEvent(new TimelineChannel(),c);
  }
  private static SourceOrder order(Object... parts) { return new SourceOrder(Arrays.asList(parts)); }
  public static void main(String[] args) {
    test("R01-empty-checkpoint-accepts",newer(context("/","owner",10,null)));
    test("R02-identical-entry-is-stale",!newer(context("/","owner",10,10L)));
    test("R03-older-entry-is-stale",!newer(context("/","owner",9,10L)));
    test("R04-strictly-newer-accepts",newer(context("/","owner",11,10L)));
    test("R05-root-E20-skips-E10",!newer(context("/","owner",10,20L)));
    test("R06-embedded-E5-accepts-E10",newer(context("/a","owner",10,5L)));
    test("R07-same-document-first-channel-ahead",!newer(context("/","first",3,9L)));
    test("R08-same-document-second-channel-empty",newer(context("/","second",3,null)));
    ChannelCheckpointContext c=context("/a","owner",6,5L);
    c.lastEvent().properties("timestamp",new Node().value(999));
    test("R09-previous-subject-is-defensive",newer(c));
    c.currentSubject().properties("timestamp",new Node().value(0));
    test("R10-current-subject-is-defensive",newer(c));
    Node now=subject(10,"different-body-same-time"), before=subject(10,"old-body");
    test("R11-different-body-same-timeline-time-not-new",!newer(ChannelCheckpointContext.of(
        "/","owner",new Node().value("raw"),"different",now,before,"old",Map.of())));
    boolean malformed=false;
    try { newer(ChannelCheckpointContext.of("/","owner",new Node().value("raw"),"e11",
        subject(11,"e11"),new Node().value("not-an-order-subject"),"bad",Map.of())); }
    catch (IllegalArgumentException expected) { malformed=true; }
    test("R12-malformed-previous-subject-rejects",malformed);
    test("R13-timestamp-before-timeline",order(1,"Z","z").compareTo(order(2,"A","a"))<0);
    test("R14-equal-time-timeline-tie",order(1,"A","z").compareTo(order(1,"B","a"))<0);
    test("R15-entry-identity-tie",order(1,"A","a").compareTo(order(1,"A","b"))<0);
    test("R16-exact-same-order-equal",order(1,"A","a").compareTo(order(1L,"A","a"))==0);
    test("R17-big-integer-order-exact",order(new BigInteger("9999999999999999999999"),"A").compareTo(
        order(new BigInteger("10000000000000000000000"),"A"))<0);
    test("R18-Unicode-codepoint-not-UTF16",order("\uE000").compareTo(order("\uD800\uDC00"))<0);
    boolean bad=false; try { order(1.5); } catch (IllegalArgumentException expected) { bad=true; }
    test("R19-noninteger-numeric-order-token-rejects",bad);
    System.out.println("{\"status\":\"PASS\",\"scope\":\"unchanged packaged class boundary, not full MyOS\",\"checks\":"+count+
        ",\"names\":[\""+String.join("\",\"",names)+"\"]}");
  }
}
