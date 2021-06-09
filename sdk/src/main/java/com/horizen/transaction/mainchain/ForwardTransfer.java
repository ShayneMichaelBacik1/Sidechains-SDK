package com.horizen.transaction.mainchain;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.horizen.block.MainchainTxForwardTransferCrosschainOutput;
import com.horizen.box.ZenBox;
import com.horizen.box.data.ZenBoxData;
import com.horizen.proposition.PublicKey25519Proposition;
import com.horizen.utils.BytesUtils;
import com.horizen.utils.Utils;
import scorex.crypto.hash.Blake2b256;

import java.util.Arrays;

public final class ForwardTransfer implements SidechainRelatedMainchainOutput<ZenBox> {

    private MainchainTxForwardTransferCrosschainOutput output;
    private byte[] containingTxHash;
    private int index;

    public ForwardTransfer(MainchainTxForwardTransferCrosschainOutput output, byte[] containingTxHash, int index) {
        this.output = output;
        this.containingTxHash = containingTxHash;
        this.index = index;
    }

    @Override
    public byte[] hash() {
        return BytesUtils.reverseBytes(Utils.doubleSHA256Hash(Bytes.concat(
                output.hash(),
                containingTxHash,
                BytesUtils.reverseBytes(Ints.toByteArray(index))
        )));
    }

    @Override
    public byte[] transactionHash() {
        return containingTxHash;
    }

    @Override
    public int transactionIndex() {
        return index;
    }

    @Override
    public byte[] sidechainId() {
        return output.sidechainId();
    }

    @Override
    public ZenBox getBox() {
        byte[] hash = Blake2b256.hash(Bytes.concat(containingTxHash, Ints.toByteArray(index)));
        long nonce = BytesUtils.getLong(hash, 0);
        return new ZenBox(
                new ZenBoxData(
                        // Note: SC output address is stored in original MC LE form, but we in SC we expect BE raw data.
                        new PublicKey25519Proposition(BytesUtils.reverseBytes(output.propositionBytes())),
                        output.amount()),
                nonce);
    }

    @Override
    public byte[] bytes() {
        return Bytes.concat(
                output.forwardTransferOutputBytes(),
                containingTxHash,
                Ints.toByteArray(index)
        );
    }

    public MainchainTxForwardTransferCrosschainOutput getFtOutput() {
        return output;
    }

    public static ForwardTransfer parseBytes(byte[] bytes) {
        if(bytes.length < 36 + MainchainTxForwardTransferCrosschainOutput.FORWARD_TRANSFER_OUTPUT_SIZE())
            throw new IllegalArgumentException("Input data corrupted.");

        int offset = 0;

        MainchainTxForwardTransferCrosschainOutput output = MainchainTxForwardTransferCrosschainOutput.create(bytes, offset).get();
        offset += MainchainTxForwardTransferCrosschainOutput.FORWARD_TRANSFER_OUTPUT_SIZE();

        byte[] txHash = Arrays.copyOfRange(bytes, offset, offset + 32);
        offset += 32;

        int idx = BytesUtils.getInt(bytes, offset);

        return new ForwardTransfer(output, txHash, idx);
    }

    @Override
    public SidechainRelatedMainchainOutputSerializer serializer() {
        return ForwardTransferSerializer.getSerializer();
    }

    @Override
    public String toString() {
        return String.format("ForwardTransfer {\ntxHash = %s\nindex = %d\nftoutput = %s\n}",
                BytesUtils.toHexString(containingTxHash), index, output);
    }
}
