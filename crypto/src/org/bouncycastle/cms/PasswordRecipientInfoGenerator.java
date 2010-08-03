package org.bouncycastle.cms;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.cms.PasswordRecipientInfo;
import org.bouncycastle.asn1.cms.RecipientInfo;
import org.bouncycastle.asn1.pkcs.PBKDF2Params;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;

public abstract class PasswordRecipientInfoGenerator
    implements RecipientInfoGenerator
{
    public static final int PKCS5_SCHEME2 = 0;
    public static final int PKCS5_SCHEME2_UTF8 = 1;

    private static Map KEYSIZES = new HashMap();
    private static Map BLOCKSIZES = new HashMap();

    static
    {
        BLOCKSIZES.put(CMSAlgorithm.DES_EDE3_CBC,  new Integer(8));
        BLOCKSIZES.put(CMSAlgorithm.AES128_CBC,  new Integer(16));
        BLOCKSIZES.put(CMSAlgorithm.AES192_CBC,  new Integer(16));
        BLOCKSIZES.put(CMSAlgorithm.AES256_CBC,  new Integer(16));

        KEYSIZES.put(CMSAlgorithm.DES_EDE3_CBC,  new Integer(192));
        KEYSIZES.put(CMSAlgorithm.AES128_CBC,  new Integer(128));
        KEYSIZES.put(CMSAlgorithm.AES192_CBC,  new Integer(192));
        KEYSIZES.put(CMSAlgorithm.AES256_CBC,  new Integer(256));
    }

    private char[] password;
    private AlgorithmIdentifier keyDerivationAlgorithm;
    private ASN1ObjectIdentifier kekAlgorithm;
    private SecureRandom random;
    private int schemeID;
    private int keySize;
    private int blockSize;

    protected PasswordRecipientInfoGenerator(ASN1ObjectIdentifier kekAlgorithm, char[] password)
    {
        this(kekAlgorithm, password, getKeySize(kekAlgorithm), ((Integer)BLOCKSIZES.get(kekAlgorithm)).intValue());
    }

    protected PasswordRecipientInfoGenerator(ASN1ObjectIdentifier kekAlgorithm, char[] password, int keySize, int blockSize)
    {
        this.password = password;
        this.schemeID = PKCS5_SCHEME2_UTF8;
        this.kekAlgorithm = kekAlgorithm;
        this.keySize = keySize;
        this.blockSize = blockSize;
    }

    private static int getKeySize(ASN1ObjectIdentifier kekAlgorithm)
    {
        Integer size = (Integer)KEYSIZES.get(kekAlgorithm);

        if (size == null)
        {
            throw new IllegalArgumentException("cannot find key size for algorithm: " +  kekAlgorithm);
        }

        return size.intValue();
    }

    public PasswordRecipientInfoGenerator setPasswordConversionScheme(int schemeID)
    {
        this.schemeID = schemeID;

        return this;
    }

    public PasswordRecipientInfoGenerator setSaltAndIterationCount(byte[] salt, int iterationCount)
    {
        this.keyDerivationAlgorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBKDF2, new PBKDF2Params(salt, iterationCount));

        return this;
    }

    public PasswordRecipientInfoGenerator setRandom(SecureRandom random)
    {
        this.random = random;

        return this;
    }

    public RecipientInfo generate(byte[] contentEncryptionKey)
        throws CMSException
    {
        byte[] iv = new byte[blockSize];     /// TODO: set IV size properly!

        if (random == null)
        {
            random = new SecureRandom();
        }
        
        random.nextBytes(iv);

        if (keyDerivationAlgorithm == null)
        {
            byte[] salt = new byte[20];

            random.nextBytes(salt);

            keyDerivationAlgorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBKDF2, new PBKDF2Params(salt, 1024));
        }

        PBKDF2Params params = PBKDF2Params.getInstance(keyDerivationAlgorithm.getParameters());
        byte[] derivedKey;

        if (schemeID == PKCS5_SCHEME2)
        {
            PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator();

            gen.init(PBEParametersGenerator.PKCS5PasswordToBytes(password), params.getSalt(), params.getIterationCount().intValue());

            derivedKey = ((KeyParameter)gen.generateDerivedParameters(keySize)).getKey();
        }
        else
        {
            PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator();

            gen.init(PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password), params.getSalt(), params.getIterationCount().intValue());

            derivedKey = ((KeyParameter)gen.generateDerivedParameters(keySize)).getKey();
        }

        AlgorithmIdentifier kekAlgorithmId = new AlgorithmIdentifier(kekAlgorithm, new DEROctetString(iv));

        byte[] encryptedKeyBytes = generateEncryptedBytes(kekAlgorithmId, derivedKey, contentEncryptionKey);

        ASN1OctetString encryptedKey = new DEROctetString(encryptedKeyBytes);

        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(kekAlgorithm);
        v.add(new DEROctetString(iv));

        AlgorithmIdentifier keyEncryptionAlgorithm = new AlgorithmIdentifier(
            PKCSObjectIdentifiers.id_alg_PWRI_KEK, new DERSequence(v));

        return new RecipientInfo(new PasswordRecipientInfo(keyDerivationAlgorithm,
            keyEncryptionAlgorithm, encryptedKey));
    }

    protected abstract byte[] generateEncryptedBytes(AlgorithmIdentifier algorithm, byte[] derivedKey, byte[] contentEncryptionKey)
        throws CMSException;
}