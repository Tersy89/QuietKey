package app.quietkey;

import java.util.*;

/**
 * Message list logic with no Android dependency: groups encrypted history into chats (one group +
 * one other member) and decides what Enigma may preview in-app. Typed messages travel as a small
 * .enigma package named {@link #TEXT_NAME}; Send as text carries the same package as pasteable text.
 */
public final class Inbox {
    public static final String TEXT_NAME="message.txt",TEXT_MIME="text/plain";
    public static final int OTHER=0,TEXT=1,IMAGE=2;
    public static final long TEXT_PREVIEW=1024L*1024,IMAGE_PREVIEW=25L*1024*1024;
    private Inbox(){}

    public static final class Chat {
        public final String group,member;public final List<MessageRecord> items=new ArrayList<>();
        Chat(String group,String member){this.group=group;this.member=member;}
        public MessageRecord last(){return items.get(items.size()-1);}
    }
    /** The other person in a conversation: the recipient of what I sent, or the sender of what I received. */
    public static String member(MessageRecord r){return r.outgoing?r.recipient:r.sender;}
    /** Chats ordered newest first; items inside each chat oldest first. */
    public static List<Chat> chats(List<MessageRecord> history){
        LinkedHashMap<String,Chat> chats=new LinkedHashMap<>();
        for(MessageRecord r:sorted(history)){String key=r.group+"\n"+member(r);Chat c=chats.get(key);if(c==null){c=new Chat(r.group,member(r));chats.put(key,c);}c.items.add(r);}
        List<Chat> out=new ArrayList<>(chats.values());out.sort((x,y)->Long.compare(y.last().createdAt,x.last().createdAt));return out;
    }
    public static List<MessageRecord> thread(List<MessageRecord> history,String group,String member){
        List<MessageRecord> out=new ArrayList<>();for(MessageRecord r:sorted(history))if(r.group.equals(group)&&member(r).equals(member))out.add(r);return out;
    }
    private static List<MessageRecord> sorted(List<MessageRecord> history){List<MessageRecord> out=new ArrayList<>(history);out.sort(Comparator.comparingLong(r->r.createdAt));return out;}
    public static boolean typed(String name,String mime){return TEXT_NAME.equals(name)&&TEXT_MIME.equals(mime);}
    public static String label(MessageRecord r){String base=TEXT_NAME.equals(r.name)&&TEXT_MIME.equals(r.mime)?"Message":r.name;return r.kind.equals("once")?base+" (view once)":base;}
    /** Only bounded images and text are opened in-app; everything else is offered as Save to device. */
    public static int preview(String mime,String name,long size){
        String m=mime==null?"":mime.toLowerCase(Locale.ROOT),n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(size<=IMAGE_PREVIEW&&(m.equals("image/jpeg")||m.equals("image/png")||m.equals("image/webp")||m.equals("image/gif")||m.equals("image/bmp")||m.equals("image/heic")||m.equals("image/heif")))return IMAGE;
        if(size<=TEXT_PREVIEW&&(m.startsWith("text/")||n.endsWith(".txt")||n.endsWith(".md")||n.endsWith(".csv")||n.endsWith(".log")))return TEXT;
        return OTHER;
    }
}
