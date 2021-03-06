package com.axlabs;

import io.neow3j.contract.GasToken;
import io.neow3j.contract.SmartContract;
import io.neow3j.crypto.ECKeyPair;
import io.neow3j.protocol.Neow3j;
import io.neow3j.protocol.core.response.NeoSendRawTransaction;
import io.neow3j.protocol.core.stackitem.StackItem;
import io.neow3j.test.ContractTest;
import io.neow3j.test.ContractTestExtension;
import io.neow3j.test.DeployConfig;
import io.neow3j.test.DeployConfiguration;
import io.neow3j.test.DeployContext;
import io.neow3j.transaction.AccountSigner;
import io.neow3j.transaction.Transaction;
import io.neow3j.transaction.exceptions.TransactionConfigurationException;
import io.neow3j.types.ContractParameter;
import io.neow3j.types.Hash160;
import io.neow3j.types.Hash256;
import io.neow3j.utils.Numeric;
import io.neow3j.wallet.Account;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static io.neow3j.types.ContractParameter.bool;
import static io.neow3j.types.ContractParameter.byteArray;
import static io.neow3j.types.ContractParameter.hash160;
import static io.neow3j.types.ContractParameter.integer;
import static io.neow3j.types.ContractParameter.string;
import static io.neow3j.utils.Await.waitUntilTransactionIsExecuted;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ContractTest(
        blockTime = 1,
        contracts = {MemeContract.class, GovernanceContract.class},
        batchFile = "neoxp.batch",
        configFile = "neoxp.neo-express"
)
public class ContractsTest {

    @RegisterExtension
    private static ContractTestExtension ext = new ContractTestExtension();

    private static Neow3j neow3j;
    private static SmartContract governanceContract;
    private static SmartContract memeContract;

    private static final int VOTING_TIME = 10;
    private static final String ALICE_SKEY =
            "84180ac9d6eb6fba207ea4ef9d2200102d1ebeb4b9c07e2c6a738a42742e27a5";
    // MemeContract owner
    private static Account a1 = new Account(
            ECKeyPair.create(Numeric.hexStringToByteArray(ALICE_SKEY)));
    private static Account a2 = Account.create();
    private static Account a3 = Account.create();
    private static Account a4 = Account.create();

    // Governance methods
    private static final String vote = "vote";
    private static final String proposeNewMeme = "proposeNewMeme";
    private static final String proposeRemoval = "proposeRemoval";
    private static final String execute = "execute";
    private static final String getVotingTime = "getVotingTime";
    private static final String getMinVotesInFavor = "getMinVotesInFavor";
    private static final String getMemeContract = "getMemeContract";
    private static final String getProposal = "getProposal";

    // Meme contract methods
    private static final String getMeme = "getMeme";
    private static final String getOwner = "getOwner";
    private static final String getMemes = "getMemes";

    private static final BigInteger votingTime = BigInteger.TEN;
    private static final BigInteger minVotesInFavor = new BigInteger("3");

    @DeployConfig(MemeContract.class)
    public static DeployConfiguration memeContractDeployConfig() {
        DeployConfiguration config = new DeployConfiguration();
        config.setDeployParam(hash160(a1));
        config.setSigner(AccountSigner.calledByEntry(a1));
        return config;
    }

    @DeployConfig(GovernanceContract.class)
    public static DeployConfiguration govContractDeployConfig(DeployContext ctx) {
        DeployConfiguration config = new DeployConfiguration();
        SmartContract memeContract = ctx.getDeployedContract(MemeContract.class);
        config.setDeployParam(hash160(memeContract.getScriptHash()));
        AccountSigner signer = AccountSigner.none(a1);
        signer.setAllowedContracts(memeContract.getScriptHash());
        config.setSigner(signer);
        return config;
    }

    @BeforeAll
    public static void setUp() throws Throwable {
        neow3j = ext.getNeow3j();
        memeContract = ext.getDeployedContract(MemeContract.class);
        governanceContract = ext.getDeployedContract(GovernanceContract.class);
        fundAccounts(a1, a2, a3, a4);
    }

    @Test
    public void testGetOwner() throws IOException {
        Hash160 memeOwner = memeContract.callFunctionReturningScriptHash(getOwner);
        assertThat(memeOwner, is(governanceContract.getScriptHash()));
    }

    @Test
    public void testGetMemeContract() throws IOException {
        Hash160 linkedMemeContract =
                governanceContract.callFunctionReturningScriptHash(getMemeContract);
        assertThat(linkedMemeContract, is(memeContract.getScriptHash()));
    }

    @Test
    public void testGetVotingTime() throws IOException {
        BigInteger votingTime = governanceContract.callFuncReturningInt(getVotingTime);
        assertThat(votingTime, is(votingTime));
    }

    @Test
    public void getMinVotesInFavor() throws IOException {
        BigInteger minVotes = governanceContract.callFuncReturningInt(getMinVotesInFavor);
        assertThat(minVotes, is(minVotesInFavor));
    }

    @Test
    public void testProposeNewMeme() throws Throwable {
        ContractParameter memeId = string("proposeNewMeme");
        Hash256 hash = setupBasicProposal(memeId, true);

        IntProposal proposal = getProposal(memeId);

        // Transaction height is 1 higher than the current index that was computed when executing
        // the script.
        // Finalization block is the last block any vote is allowed.
        BigInteger txHeight = neow3j.getTransactionHeight(hash).send().getHeight();
        assertThat(proposal.finalizationBlock,
                is(txHeight.add(votingTime).subtract(BigInteger.ONE)));

        assertTrue(proposal.create);
        assertTrue(proposal.voteInProgress);
        assertThat(proposal.votesInFavor, is(BigInteger.ZERO));
        assertThat(proposal.votesAgainst, is(BigInteger.ZERO));
    }

    @Test
    public void testVote() throws Throwable {
        ContractParameter memeId = string("testVote");
        setupBasicProposal(memeId, true);

        Hash256 voteFor1 = vote(memeId, a1, true);
        Hash256 voteFor2 = vote(memeId, a2, true);
        Hash256 voteFor3 = vote(memeId, a3, true);
        waitUntilTransactionIsExecuted(voteFor1, neow3j);
        waitUntilTransactionIsExecuted(voteFor2, neow3j);
        waitUntilTransactionIsExecuted(voteFor3, neow3j);

        IntProposal proposal = getProposal(memeId);

        assertThat(proposal.votesInFavor, is(new BigInteger("3")));
        assertThat(proposal.votesAgainst, is(BigInteger.ZERO));

        Hash256 voteAgainst = vote(memeId, a4, false);
        waitUntilTransactionIsExecuted(voteAgainst, neow3j);
        try {
            vote(memeId, a4, false);
            fail("Account that already voted, should not be able to vote again.");
        } catch (TransactionConfigurationException e) {
            assertThat(e.getMessage(), containsString("Already voted"));
        }

        proposal = getProposal(memeId);
        assertThat(proposal.votesInFavor, is(new BigInteger("3")));
        assertThat(proposal.votesAgainst, is(BigInteger.ONE));
    }

    @Test
    public void testExecuteCreation() throws Throwable {
        String memeIdString = "executeCreation";
        ContractParameter memeId = string(memeIdString);
        String description = "coolDescriptionString";
        String url = "AxLabsUrlString";
        String imgHash = "ae51b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        createProposal(memeId, description, url, imgHash);

        Hash256 voteFor1 = vote(memeId, a1, true);
        Hash256 voteFor2 = vote(memeId, a2, true);
        Hash256 voteFor3 = vote(memeId, a3, true);
        waitUntilTransactionIsExecuted(voteFor1, neow3j);
        waitUntilTransactionIsExecuted(voteFor2, neow3j);
        waitUntilTransactionIsExecuted(voteFor3, neow3j);

        ext.fastForward(VOTING_TIME);

        Hash256 exec = execProp(memeId, a4);
        waitUntilTransactionIsExecuted(exec, neow3j);

        List<StackItem> meme = memeContract.callInvokeFunction(getMeme, asList(memeId))
                .getInvocationResult().getStack().get(0).getList();
        assertThat(meme, hasSize(4));
        assertThat(meme.get(0).getString(), is(memeIdString));
        assertThat(meme.get(1).getString(), is(description));
        assertThat(meme.get(2).getString(), is(url));
        assertThat(meme.get(3).getHexString(), is(imgHash));
    }

    @Test
    public void testExecuteRemoval() throws Throwable {
        ContractParameter memeId = string("executeRemoval");
        createMemeThroughVote(memeId);
        removeProposal(memeId);

        Hash256 voteFor1 = vote(memeId, a1, true);
        Hash256 voteFor2 = vote(memeId, a2, true);
        Hash256 voteFor3 = vote(memeId, a3, true);
        Hash256 voteAgainst4 = vote(memeId, a4, false);
        waitUntilTransactionIsExecuted(voteFor1, neow3j);
        waitUntilTransactionIsExecuted(voteFor2, neow3j);
        waitUntilTransactionIsExecuted(voteFor3, neow3j);
        waitUntilTransactionIsExecuted(voteAgainst4, neow3j);

        ext.fastForward(VOTING_TIME);

        Hash256 exec = execProp(memeId, a3);
        waitUntilTransactionIsExecuted(exec, neow3j);

        String exception = memeContract.callInvokeFunction(getMeme, asList(memeId))
                .getInvocationResult().getException();
        // Check whether the meme was successfully removed.
        assertThat(exception, containsString("No meme found for this id."));
    }

    // Creates a proposal that is not accepted and creates a new proposal with the same meme id.
    // This should overwrite the existing proposal.
    @Test
    public void testOverwriteUnacceptedCreateProposal() throws Throwable {
        String memeIdString = "overwriteUnacceptedCreateProposal";
        ContractParameter memeId = string(memeIdString);
        String imgHash1 = "ae51b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        createProposal(memeId, "description1", "url1", imgHash1);

        // Fast-forward till proposal is finalized.
        ext.fastForward(VOTING_TIME);

        String imgHash2 = "1051b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        createProposal(memeId, "description2", "url2", imgHash2);
        Hash256 voteFor1 = vote(memeId, a1, true);
        Hash256 voteFor2 = vote(memeId, a2, true);
        Hash256 voteFor3 = vote(memeId, a3, true);
        waitUntilTransactionIsExecuted(voteFor1, neow3j);
        waitUntilTransactionIsExecuted(voteFor2, neow3j);
        waitUntilTransactionIsExecuted(voteFor3, neow3j);

        // Fast-forward till proposal is finalized.
        ext.fastForward(VOTING_TIME);

        Hash256 exec = execProp(memeId, a3);
        waitUntilTransactionIsExecuted(exec, neow3j);

        List<StackItem> meme = memeContract.callInvokeFunction(getMeme, asList(memeId))
                .getInvocationResult().getStack().get(0).getList();
        assertThat(meme, hasSize(4));
        assertThat(meme.get(0).getString(), is(memeIdString));
        assertThat(meme.get(1).getString(), is("description2"));
        assertThat(meme.get(2).getString(), is("url2"));
        assertThat(meme.get(3).getHexString(), is(imgHash2));
    }

    // Creates a proposal that is not accepted and creates a new proposal with the same meme id.
    // This should overwrite the existing proposal.
    @Test
    public void testOverwriteUnacceptedRemoveProposal() throws Throwable {
        ContractParameter memeId = string("testOverwriteUnacceptedRemoveProposal");
        createMemeThroughVote(memeId);

        removeProposal(memeId);
        ext.fastForward(VOTING_TIME);

        removeProposal(memeId);

        Hash256 voteFor1 = vote(memeId, a1, true);
        Hash256 voteFor2 = vote(memeId, a2, true);
        Hash256 voteFor3 = vote(memeId, a3, true);
        waitUntilTransactionIsExecuted(voteFor1, neow3j);
        waitUntilTransactionIsExecuted(voteFor2, neow3j);
        waitUntilTransactionIsExecuted(voteFor3, neow3j);

        ext.fastForward(VOTING_TIME);

        Hash256 exec = execProp(memeId, a3);
        waitUntilTransactionIsExecuted(exec, neow3j);

        String exception = memeContract.callInvokeFunction(getMeme, asList(memeId))
                .getInvocationResult().getException();
        // Check whether the meme was successfully removed.
        assertThat(exception, containsString("No meme found for this id."));
    }

    @Test
    public void testGetMemes() throws Throwable {
        ContractParameter memeId1 = string("getMemes1");
        ContractParameter memeId2 = string("getMemes2");
        ContractParameter memeId3 = string("getMemes3");
        ContractParameter memeId4 = string("getMemes4");
        String imgHash1 = "1051b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        String imgHash2 = "2051b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        String imgHash3 = "3051b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        String imgHash4 = "4051b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275";
        createMemeThroughVote(memeId1, "d1", "u1", imgHash1);
        createMemeThroughVote(memeId2, "d2", "u2", imgHash2);
        createMemeThroughVote(memeId3, "d3", "u3", imgHash3);
        createMemeThroughVote(memeId4, "d4", "u4", imgHash4);

        List<StackItem> memes = memeContract.callInvokeFunction(getMemes, asList(integer(0)))
                .getInvocationResult().getStack().get(0).getList();

        assertThat(memes, hasSize(4));

        List<StackItem> meme = memes.get(0).getList();
        assertThat(meme.get(0).getString(), is("getMemes1"));
        assertThat(meme.get(1).getString(), is("d1"));
        assertThat(meme.get(2).getString(), is("u1"));
        assertThat(meme.get(3).getHexString(), is(imgHash1));

        meme = memes.get(3).getList();
        assertThat(meme.get(0).getString(), is("getMemes4"));
        assertThat(meme.get(1).getString(), is("d4"));
        assertThat(meme.get(2).getString(), is("u4"));
        assertThat(meme.get(3).getHexString(), is(imgHash4));
    }

    private static void fundAccounts(Account... accounts) throws Throwable {
        ContractTestExtension.GenesisAccount genesis = ext.getGenesisAccount();
        GasToken gasToken = new GasToken(neow3j);
        BigInteger amount = gasToken.toFractions(new BigDecimal("2000"));
//        BigInteger minAmount = gasToken.toFractions(new BigDecimal("500"));
        List<Hash256> txHashes = new ArrayList<>();
        for (Account a : accounts) {
            Transaction tx = gasToken.transfer(genesis.getMultiSigAccount().getScriptHash(),
                            a.getScriptHash(), amount)
                    .signers(AccountSigner.calledByEntry(genesis.getMultiSigAccount()))
                    .getUnsignedTransaction();
            Hash256 txHash = tx.addMultiSigWitness(
                            genesis.getMultiSigAccount().getVerificationScript(),
                            genesis.getSignerAccounts())
                    .send().getSendRawTransaction().getHash();
            txHashes.add(txHash);
            System.out.println("Funded account " + a.getAddress());
        }
        for (Hash256 h : txHashes) {
            waitUntilTransactionIsExecuted(h, neow3j);
        }
    }

    private Hash256 createProposal(ContractParameter memeId, String description,
            String url, String imgHash) throws Throwable {
        Hash256 hash = governanceContract.invokeFunction(proposeNewMeme,
                        memeId, string(description), string(url), byteArray(imgHash))
                .signers(AccountSigner.calledByEntry(a1))
                .sign().send().getSendRawTransaction().getHash();
        waitUntilTransactionIsExecuted(hash, neow3j);
        return hash;
    }

    private Hash256 removeProposal(ContractParameter memeId) throws Throwable {
        Hash256 hash = governanceContract.invokeFunction(proposeRemoval, memeId)
                .signers(AccountSigner.calledByEntry(a1))
                .sign().send().getSendRawTransaction().getHash();
        waitUntilTransactionIsExecuted(hash, neow3j);
        return hash;
    }

    private Hash256 setupBasicProposal(ContractParameter memeId, boolean create) throws Throwable {
        if (create) {
            return createProposal(memeId, "desc", "url",
                    "ae51b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275");
        } else {
            return removeProposal(memeId);
        }
    }

    private Hash256 vote(ContractParameter memeId, Account a, boolean inFavor) throws Throwable {
        NeoSendRawTransaction sendRawTransaction = governanceContract.invokeFunction(
                        vote, memeId, hash160(a.getScriptHash()), bool(inFavor))
                .signers(AccountSigner.calledByEntry(a))
                .sign().send();
        return sendRawTransaction.getSendRawTransaction().getHash();
    }

    private Hash256 execProp(ContractParameter memeId, Account a) throws Throwable {
        return governanceContract.invokeFunction(execute, memeId)
                .signers(AccountSigner.calledByEntry(a))
                .sign().send().getSendRawTransaction().getHash();
    }

    private void createMemeThroughVote(ContractParameter memeId, String description, String url,
            String imgHash) throws Throwable {
        createProposal(memeId, description, url, imgHash);

        Hash256 voteFor1 = vote(memeId, a1, true);
        Hash256 voteFor2 = vote(memeId, a2, true);
        Hash256 voteFor3 = vote(memeId, a3, true);
        waitUntilTransactionIsExecuted(voteFor1, neow3j);
        waitUntilTransactionIsExecuted(voteFor2, neow3j);
        waitUntilTransactionIsExecuted(voteFor3, neow3j);

        ext.fastForward(VOTING_TIME);

        Hash256 exec = execProp(memeId, a1);
        waitUntilTransactionIsExecuted(exec, neow3j);
    }

    private void createMemeThroughVote(ContractParameter memeId) throws Throwable {
        createMemeThroughVote(memeId, "coolDescription", "AxLabsUrl",
                "ae51b3d6f4876cd78e284c07003c41550741042b23b5bd13973cb16cac197275");
    }

    private static IntProposal getProposal(ContractParameter memeId) throws IOException {
        List<StackItem> proposalItem = governanceContract
                .callInvokeFunction(getProposal, asList(memeId))
                .getInvocationResult().getStack().get(0).getList();
        IntMeme meme = getMemeFromStackItem(proposalItem.get(0));
        boolean create = proposalItem.get(1).getBoolean();
        boolean voteInProgress = proposalItem.get(2).getBoolean();
        BigInteger finalizationBlock = proposalItem.get(3).getInteger();
        BigInteger votesInFavor = proposalItem.get(4).getInteger();
        BigInteger votesAgainst = proposalItem.get(5).getInteger();
        return new IntProposal(meme, create, voteInProgress, finalizationBlock, votesInFavor,
                votesAgainst);
    }

    private static IntMeme getMemeFromStackItem(StackItem memeItem) {
        List<StackItem> meme = memeItem.getList();
        String id = meme.get(0).getString();
        String description = meme.get(1).getString();
        String url = meme.get(2).getString();
        String imageHash = meme.get(3).getString();
        return new IntMeme(id, description, url, imageHash);
    }

    public static class IntProposal {
        public IntMeme meme;
        public Boolean create;
        public Boolean voteInProgress;
        public BigInteger finalizationBlock;
        public BigInteger votesInFavor;
        public BigInteger votesAgainst;

        public IntProposal(IntMeme meme, Boolean create, Boolean voteInProgress,
                BigInteger finalizationBlock, BigInteger votesInFavor, BigInteger votesAgainst) {
            this.meme = meme;
            this.create = create;
            this.voteInProgress = voteInProgress;
            this.finalizationBlock = finalizationBlock;
            this.votesInFavor = votesInFavor;
            this.votesAgainst = votesAgainst;
        }
    }

    public static class IntMeme {
        public String id;
        public String description;
        public String url;
        public String imageHash;

        public IntMeme(String id, String description, String url, String imageHash) {
            this.id = id;
            this.description = description;
            this.url = url;
            this.imageHash = imageHash;
        }
    }

}
