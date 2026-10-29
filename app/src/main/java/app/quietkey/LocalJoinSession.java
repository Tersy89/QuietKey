package app.quietkey;

import java.io.*;
import java.util.concurrent.*;

/** A single duplex exchange; vault verification/approval stays with MainActivity. */
public final class LocalJoinSession {
    public interface Approval {
        CompletableFuture<String> invite(String request);
        CompletableFuture<String> accept(String invitation);
        CompletableFuture<Void> receipt(String receipt);
        void status(String text);
    }
    private static <T> T waitFor(CompletableFuture<T> approval,long deadline)throws Exception{
        long remaining=deadline-System.nanoTime();if(remaining<=0)throw new TimeoutException("Joining expired");return approval.get(remaining,TimeUnit.NANOSECONDS);
    }
    public static void exchange(LocalJoinWire wire,boolean owner,String request,Approval approval,long deadline)throws Exception{
        if(owner){approval.status("Connected. Checking their device…");String incoming=wire.receive(LocalJoinWire.REQUEST);
            // Invalid signed requests never reach owner approval.
            JoinProtocol.parseRequest(incoming);String invitation=waitFor(approval.invite(incoming),deadline);
            wire.send(LocalJoinWire.INVITATION,invitation);approval.status("Invitation sent. Waiting for them to join…");
            waitFor(approval.receipt(wire.receive(LocalJoinWire.RECEIPT)),deadline);wire.send(LocalJoinWire.DONE,"Joined");
        }else{JoinProtocol.parseRequest(request);wire.send(LocalJoinWire.REQUEST,request);approval.status("Connected. Waiting for the owner to approve…");
            String receipt=waitFor(approval.accept(wire.receive(LocalJoinWire.INVITATION)),deadline);wire.send(LocalJoinWire.RECEIPT,receipt);approval.status("Group saved. Confirming with owner…");wire.receive(LocalJoinWire.DONE);
        }
    }
}
