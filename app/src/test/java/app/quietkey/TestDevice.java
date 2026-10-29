package app.quietkey;

/** A test member: 0.7.0 identity plus its key pools, generated with motion input mixed in. */
final class TestDevice {
    static final Entropy E=new Entropy();
    static{E.addMotion(1,1L,new float[]{0.1f,9.8f,0.2f});}
    final Identity id=Identity.generate(E);
    final KeyPools pools=new KeyPools();
    final String fp=id.fingerprint();
}
