const { Web3 } = require('web3');
const fs = require('fs');
const path = require('path');
const web3 = new Web3('http://localhost:8545');

// Sending account and private key
const account = '0x627306090abaB3A6e1400e9345bC60c78a8BEf57';
const privateKey = 'c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3';

// Log file path
const logFilePath = path.join(__dirname, 'transaction_concurrent_logs.txt');

// Get the current timestamp
function getCurrentTimestamp() {
    return new Date().toISOString();
}

// Write logs to a file
function writeLog(message) {
    fs.appendFileSync(logFilePath, `${message}\n`, 'utf8');
}

// BigInt serialization support
function serializeBigInt(obj) {
    return JSON.stringify(obj, (key, value) =>
        typeof value === 'bigint' ? value.toString() : value, 2);
}

// Function to send a single transaction
async function sendTx(tx, index) {
    try {
        const currentNonce = await web3.eth.getTransactionCount(account, 'pending');
        tx.nonce = currentNonce; // Assign a dynamic nonce

        const signedTx = await web3.eth.accounts.signTransaction(tx, privateKey);
        const receipt = await web3.eth.sendSignedTransaction(signedTx.rawTransaction);

        const logMessage = `[${getCurrentTimestamp()}] [${index}] 트랜잭션 전송 성공: ${receipt.transactionHash}`;
        console.log(logMessage);

        // Detailed receipt logging
        const detailedReceipt = serializeBigInt(receipt);
        writeLog(logMessage);
        writeLog(`[${index}] 트랜잭션 영수증 상세:\n${detailedReceipt}`);
        return receipt;
    } catch (error) {
        const logMessage = `[${getCurrentTimestamp()}] [${index}] 트랜잭션 전송 오류: ${error.message}`;
        console.error(logMessage);
        writeLog(logMessage);
        return null;
    }
}

// Function to send multiple transactions concurrently
async function sendMultipleTxsConcurrently(numTxs) {
    // Initialize logs
    fs.writeFileSync(logFilePath, '', 'utf8');
    writeLog('트랜잭션 로그 초기화 완료');

    // Generate a batch of transactions
    const txs = Array.from({ length: numTxs }, (_, i) => ({
        from: account,
        to: `0xf17f52151EbEF6C7334FAD080c5704D77216b732`, // Same recipient
        value: web3.utils.toWei((i + 1).toString(), 'wei'), // Incremental values
        gas: 21000,
        gasPrice: 0,
        chainId: 1337,
        // Nonce will be dynamically assigned later
    }));

    // Send all transactions concurrently
    const promises = txs.map((tx, index) => sendTx(tx, index + 1));
    const results = await Promise.all(promises);

    const successCount = results.filter(r => r !== null).length;
    writeLog(`모든 트랜잭션 전송 완료: ${successCount} 건 성공`);
    console.log(`모든 트랜잭션 전송 완료: ${successCount} 건 성공`);
}

// Start sending transactions concurrently
sendMultipleTxsConcurrently(10).catch(err => {
    console.error('트랜잭션 전송 중 오류 발생:', err.message);
    writeLog(`트랜잭션 전송 중 오류 발생: ${err.message}`);
});