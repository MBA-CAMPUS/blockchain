const { Web3 } = require('web3');
const fs = require('fs');
const path = require('path');
const web3 = new Web3('http://localhost:8545'); // Besu 노드의 JSON-RPC 엔드포인트

// 보내는 계정과 개인 키
const account = '0x627306090abaB3A6e1400e9345bC60c78a8BEf57';
const privateKey = 'c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3';

// 로그 파일 경로 설정
const logFilePath = path.join(__dirname, 'transaction_logs.txt');

// BigInt를 문자열로 변환하는 함수
function replaceBigInts(obj) {
    if (typeof obj === 'bigint') {
        return obj.toString();
    } else if (Array.isArray(obj)) {
        return obj.map(replaceBigInts);
    } else if (obj !== null && typeof obj === 'object') {
        const newObj = {};
        for (const key in obj) {
            if (Object.prototype.hasOwnProperty.call(obj, key)) {
                newObj[key] = replaceBigInts(obj[key]);
            }
        }
        return newObj;
    }
    return obj;
}

// 트랜잭션 객체 생성
const tx = {
    from: account,
    to: '0xf17f52151ebef6c7334fad080c5704d77216b732',
    value: '0x1', // 1 wei
    gas: 21000,
    gasPrice: '0', // 가스 가격을 0으로 설정
    chainId: 1337 // Genesis 파일의 chainId
};

// 트랜잭션 서명 및 전송
async function sendTx(index) {
    try {
        const signedTx = await web3.eth.accounts.signTransaction(tx, privateKey);
        const receipt = await web3.eth.sendSignedTransaction(signedTx.rawTransaction);

        // BigInt를 문자열로 변환
        const safeReceipt = replaceBigInts(receipt);

        // 로그 메시지 생성
        const logMessage = `트랜잭션 ${index}: ${JSON.stringify(safeReceipt)}\n`;

        // 로그 파일에 추가
        fs.appendFile(logFilePath, logMessage, (err) => {
            if (err) {
                console.error(`트랜잭션 ${index} 로그 저장 오류:`, err);
            }
        });
    } catch (error) {
        // 오류 객체에도 BigInt가 있을 수 있으므로 변환
        const safeError = replaceBigInts(error);
        const errorMessage = `트랜잭션 ${index} 전송 오류: ${JSON.stringify(safeError)}\n`;
        fs.appendFile(logFilePath, errorMessage, (err) => {
            if (err) {
                console.error(`트랜잭션 ${index} 오류 로그 저장 실패:`, err);
            }
        });
        console.error(`트랜잭션 ${index} 전송 오류:`, error);
    }
}

// 다수의 트랜잭션 전송
async function sendMultipleTx(count) {
    for (let i = 1; i <= count; i++) {
        await sendTx(i);
        console.log(`트랜잭션 ${i}/${count} 전송 완료`);
    }
}

// 매개변수의 숫자만큼 트랜잭션 전송
sendMultipleTx(5);