const { Web3 } = require('web3');
const fs = require('fs');
const path = require('path');
const web3 = new Web3('http://localhost:8545');

// 보내는 계정과 개인 키
const account = '0x627306090abaB3A6e1400e9345bC60c78a8BEf57';
const privateKey = 'c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3';

// 로그 파일 경로 설정
const logFilePath = path.join(__dirname, 'transaction_parallel_logs.txt');

// 현재 시각을 가져오는 함수
function getCurrentTimestamp() {
    return new Date().toISOString();
}

// 로그 기록 함수
function writeLog(message) {
    fs.appendFileSync(logFilePath, `${message}\n`, 'utf8');
}

// BigInt 직렬화 지원 함수
function serializeBigInt(obj) {
    return JSON.stringify(obj, (key, value) => (typeof value === 'bigint' ? value.toString() : value), 2);
}

// 트랜잭션 서명 및 전송 함수
async function sendTx(tx, index) {
    try {
        const signedTx = await web3.eth.accounts.signTransaction(tx, privateKey);
        const receipt = await web3.eth.sendSignedTransaction(signedTx.rawTransaction);
        const logMessage = `[${getCurrentTimestamp()}] [${index}] 트랜잭션 전송 성공: ${receipt.transactionHash}`;
        console.log(logMessage);

        // 영수증 전체를 JSON 형식으로 로그 파일에 기록
        const detailedReceipt = serializeBigInt(receipt); // BigInt 직렬화
        writeLog(logMessage);
        writeLog(`[${index}] 트랜잭션 영수증 상세:\n${detailedReceipt}`);
        return receipt;
    } catch (error) {
        const logMessage = `[${getCurrentTimestamp()}] [${index}] 트랜잭션 전송 오류: ${error.message}`;
        console.error(logMessage);
        writeLog(logMessage); // 오류도 로그 파일에 기록
        return null;
    }
}

// 병렬 트랜잭션 전송 함수
async function sendMultipleTxs(numTxs) {
    // 로그 초기화
    fs.writeFileSync(logFilePath, '', 'utf8');
    writeLog('트랜잭션 로그 초기화 완료');

    // Nonce 값 가져오기 및 변환
    const nonceBigInt = await web3.eth.getTransactionCount(account);
    const nonce = Number(nonceBigInt);

    // 트랜잭션 객체 배열 생성
    const txs = Array.from({ length: numTxs }, (_, i) => ({
        from: account,
        to: `0xf17f52151EbEF6C7334FAD080c5704D77216b732`, // 동일한 주소로 전송
        value: web3.utils.toWei((i + 1).toString(), 'wei'), // 다른 금액 전송 (1 wei, 2 wei, ...)
        gas: 21000,
        gasPrice: 0,
        chainId: 1337,
        nonce: nonce + i, // 숫자 연산 가능
    }));

    // Promise.all을 사용하여 병렬로 트랜잭션 전송
    const promises = txs.map((tx, index) => sendTx(tx, index + 1));
    const receipts = await Promise.all(promises);

    const successCount = receipts.filter(r => r !== null).length;
    writeLog(`모든 트랜잭션 전송 완료: ${successCount} 건 성공`);
    console.log(`모든 트랜잭션 전송 완료: ${successCount} 건 성공`);
}

// 병렬로 매개변수의 값만큼 트랜잭션 전송
sendMultipleTxs(10);
