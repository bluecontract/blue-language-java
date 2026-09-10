package blue.coordination.internal;

/** Exercises the unchanged packaged occurrence-history cursor. */
public final class CursorRuntimeProbe {
  private static int count;
  private static void test(boolean b) { if(!b)throw new AssertionError("cursor check "+count);count++; }
  public static void main(String[] args) {
    EmbeddedEpochCursor c=new EmbeddedEpochCursor("B:/a:g1",5);
    EmbeddedEpochCursor next=c.advanceTo(6);
    test(c.appliedChildEpoch()==5);test(next.appliedChildEpoch()==6);
    boolean gap=false; try {c.advanceTo(7);}catch(IllegalStateException expected){gap=true;}
    test(gap);
    boolean duplicate=false;try {next.advanceTo(6);}catch(IllegalStateException expected){duplicate=true;}
    test(duplicate);
    EmbeddedEpochCursor other=new EmbeddedEpochCursor("B:/other:g1",5).advanceTo(6);
    test(!next.bindingId().equals(other.bindingId()));
    EmbeddedEpochCursor fresh=new EmbeddedEpochCursor("B:/a:g2",0);
    test(!next.bindingId().equals(fresh.bindingId())&&fresh.appliedChildEpoch()==0);
    System.out.println("{\"status\":\"PASS\",\"scope\":\"unchanged packaged cursor class, not full catch-up\",\"checks\":"+count+"}");
  }
}
