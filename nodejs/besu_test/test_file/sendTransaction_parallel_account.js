const { Web3 } = require('web3');
const fs = require('fs');
const path = require('path');
const web3 = new Web3('http://localhost:8545');


// BigInt를 처리하는 커스텀 replacer 함수
function bigIntReplacer(key, value) {
    if (typeof value === 'bigint') {
        return value.toString();
    }
    return value;
}

// 트랜잭션 로그 파일 경로
const logFilePath = path.join(__dirname, 'transaction_parallel_account_logs.txt');


//새로 생성한 계정들에게 이더를 보낼 계정과 개인 키 (이더를 가지고 있는 계정)
// => 새로 생성한 계정들은 이더가 없음. 이 돈 채우기 용 계정이라 생각하면됨
const fromAccount = {
    address: '0x627306090abaB3A6e1400e9345bC60c78a8BEf57', // 이더 보내는 계정 주소
    privateKey: 'c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3' // 이더 보내는 계정 개인 키
};


// accounts 배열에 원하는 만큼 객체로 각 계정을 생성하고, 해당 계정의 개인 키를 저장
function createAccounts(numAccounts) {
    const accounts = [];
    for (let i = 0; i < numAccounts; i++) {
        const newAccount = web3.eth.accounts.create();
        accounts.push({
            address: newAccount.address,
            privateKey: newAccount.privateKey
        });
    }
    return accounts;
}

// 로그 기록 함수
function writeLog(message) {
    fs.appendFileSync(logFilePath, `${message}\n`, 'utf8');
}

// 트랜잭션 서명 및 전송 함수
// => 이게 새 계정 생성해서 트랜잭션 전송했을때의 최정 결과 로그기록이라 보면됨
async function sendTx(tx, account, index) {
    try {
        const signedTx = await web3.eth.accounts.signTransaction(tx, account.privateKey);
        const receipt = await web3.eth.sendSignedTransaction(signedTx.rawTransaction);
        const logMessage = `[${new Date().toISOString()}] [${index}] 트랜잭션 전송 성공: ${receipt.transactionHash}`;
        console.log(logMessage);

        // 로그 파일에 기록
        writeLog(logMessage);
        writeLog(`[${index}] 트랜잭션 영수증: ${JSON.stringify(receipt, bigIntReplacer, 2)}`);
        return receipt;
    } catch (error) {
        const logMessage = `[${new Date().toISOString()}] [${index}] 트랜잭션 전송 오류: ${error.message}`;
        console.error(logMessage);
        writeLog(logMessage); // 오류도 로그 파일에 기록
        return null;
    }
}

// 트랜잭션을 보내기 전에 새 계정에 이더를 할당하는 함수
async function fundAccounts(accounts) {

    // 로그 초기화
    fs.writeFileSync(logFilePath, '', 'utf8');
    writeLog('트랜잭션 로그 초기화 완료');

    //이더를 보유하고 있는 계정의 nonce 값 가져오기
    let nonce_rich_account = await web3.eth.getTransactionCount(fromAccount.address, 'pending');

    // 송금할 금액 설정 (1 이더를 Wei로 변환)
    const amountInWei = web3.utils.toWei('1', 'ether').toString();  // 각 새 계정에 1 이더 전송
    // 새 계정으로 이더 보내기
    for (let i = 0; i < accounts.length; i++) {
        const toAccount = accounts[i];
        const tx = {
            from: fromAccount.address,
            to: toAccount.address,
            value: amountInWei,
            gas: 21000,
            gasPrice: 0,
            nonce: nonce_rich_account, //이더를 이빠이 보유한 계정 관련 트랜잭션 마다 고유 nonce 값 할당
        };
        nonce_rich_account++; //다음 반복문 로직을 위해 nonce 값 증가 처리

        const signedTx = await web3.eth.accounts.signTransaction(tx, fromAccount.privateKey);
        const receipt = await web3.eth.sendSignedTransaction(signedTx.rawTransaction);
        writeLog(`계정 ${toAccount.address}에 1 이더 송금 완료: ${receipt.transactionHash}`);
        console.log(`계정 ${toAccount.address}에 1 이더 송금 완료: ${receipt.transactionHash}`);
    }
}


// 병렬로 모든 계정이 트랜잭션을 보내는 함수
async function sendMultipleTxs(accounts, numTxs) {

    //accounts 변수는 내가 만든 계정의 개수
    //numTxs 는 내가 생성하고 싶은 트랜잭션의 개수

    // 계정별 nonce 값 독립적으로 관리
    const nonces = {}; // 각 계정의 nonce를 관리하는 객체
    //  => 이게 계정별로 nonce 값을 독립적으로 관리시키기 위한 설정
    //  => 계정들 간에 nonce 값이 겹치는 건 상관없음. 한 계정이 가지고 있는 nonce 값이 겹쳐선 안되는거임

    // 모든 계정이 트랜잭션을 보내도록 설정
    const txs = []; // 트랜잭션 객체를 저장해둘 배열
    for (let i = 0; i < numTxs; i++) {
        const account = accounts[i % accounts.length]; // 계정을 순차적으로 할당
        // 이게 규칙 생각해 본다면
        // 보내고 싶은 트랜잭션 10개
        // 생성한 계정의 수는 5개 라고 가정하면
        // (계정들의 개수) ÷ (만들고 싶은 트랜잭션 총수) 의 나머지

        // account[0] => 0번째 계정 => 0번째 트랜잭션 보내기
        // account[1] => 1번째 계정 => 1번째 트랜잭션 보내기
        // account[2] => 2번째 계정 => 2번째 트랜잭션 보내기
        // account[3] => 3번째 계정 => 3번째 트랜잭션 보내기
        // account[4] => 4번째 계정 => 4번째 트랜잭션 보내기
        // account[0] => 0번째 계정 => 5번째 트랜잭션 보내기
        // account[1] => 1번째 계정 => 6번째 트랜잭션 보내기
        // account[2] => 2번째 계정 => 7번째 트랜잭션 보내기
        // account[3] => 3번째 계정 => 8번째 트랜잭션 보내기
        // account[4] => 4번째 계정 => 9번째 트랜잭션 보내기

        // 각 계정의 nonce 값을 독립적으로 관리
        if (nonces[account.address] === undefined) {
            nonces[account.address] = await web3.eth.getTransactionCount(account.address, 'pending'); // 계정의 주소,즉 지정된 계정에서 현재까지 발생한 트랜잭션의 수를 가져오는 메서드
            //pending: 네트워크상에서 아직 확정되지 않은 트랜잭션들(트랜잭션 풀의 트랜잭션들) + 확정된 트랜잭션들을 집계해 해당 계정의 nonce 값을 계산하는 기준
            //latest: 네트워크상에서 확정된 트랜잭션들만 집계 해 해당 계정의 nonce 값을 계산하는 기준
            // 계정의 주소값을 식별값을 key 값으로 해서 해당 계정의 nonce 값을 가져와서 기록해 관리한다고 보면됨
        }
        // 각 계정의 nonce 값을 고유하게 증가
        const nonce = nonces[account.address];
        nonces[account.address]++; // nonce 증가

        // 트랜잭션 객체 생성
        txs.push({
            from: account.address,
            to: '0xf17f52151EbEF6C7334FAD080c5704D77216b732', // 수신자
            value: web3.utils.toWei(1, 'wei').toString(),
            gas: 21000,
            gasPrice: 0,
            chainId: 1337,
            nonce: nonce, // 고유한 nonce 할당
        });
    }

    // Promise.all을 사용하여 병렬로 트랜잭션 전송
    const promises = txs.map((tx, index) => sendTx(tx, accounts[index % accounts.length], index + 1));
    const receipts = await Promise.all(promises);

    const successCount = receipts.filter(r => r !== null).length;
    writeLog(`모든 트랜잭션 전송 완료: ${successCount} 건 성공`);
    console.log(`모든 트랜잭션 전송 완료: ${successCount} 건 성공`);
}

// 계정 수와 트랜잭션 수 설정
const num_tx_and_account = 22; // 생성하길 원하는 계정수 및 전송하길 원하는 트랜잭션 수 => 1 계정 당 1개 씩 트랜잭션을 보낸다고 생각하면됨

// 계정 생성
const accounts = createAccounts(num_tx_and_account);

// 새 계정에 이더를 할당
fundAccounts(accounts).then(() => {
    // 트랜잭션 보내기
    sendMultipleTxs(accounts, num_tx_and_account).catch(err => {
        console.error('트랜잭션 전송 중 오류 발생:', err.message);
    });
}).catch(err => {
    console.error('이더 할당 중 오류 발생:', err.message);
});




//에러1
//Transaction nonce is too distant from current sender nonce
// if (!nonces[account.address]) {
//     nonces[account.address] = await web3.eth.getTransactionCount(account.address, 'pending');
// }
// 이건 1개의 계정이 여러개의 트랜잭션을 보낼때 어떤 문제가 있어 보임
// 우선 필요한건 여러개의 계정이 동시에 트랜잭션을 보내면 되는 것이기에 계정 수와 트랜잭션 수를 일치시켜 해결했음


//에러2
//Cannot mix BigInt and other types, use explicit conversions
//const nonce = nonces[account.address];
//nonces[account.address]++;
//이런 nonce 값을 저장하는 객체가 타입이 bigint 임, ++로 값을 1씩 증가시켜 주던가 아니면 BigInt(nonce) + BigInt(1); 처럼해서 bigint 타입으로 맞춰서 계산처리해줘야함


//에러3
//Transaction started at 4388 but was not mined within 50 blocks. Please make sure your transaction was properly sent and there are no previous pending transaction for the same account. However, be aware that it might still be mined!
// 가스 price 0 말고 조금이라도 줘서 해결, 원인은 알아봐야함.
// 이거 트랜잭션 풀 size를 2개로 제한해둬서 이런 것 같음. 나중에 가스 0으로 해서 다시 테스트해보니 돌아감, 
// gas 1이라도 줬을떄 돌아갔던 이유는 트랜잭션 처리시 가스 관련 계산 처리가 추가되다보니 연산처리로인한 지연시간이 생겨 트랜잭션 풀이 최대 2개까지 넣어도 감당 가능한 상태였던 걸로 보임

//마지막 부분 원래 의도 했던 로직
// // 계정 수와 트랜잭션 수 설정
// const numAccounts = 5; // 원하는 계정 수
// const numTxs = 10; // 트랜잭션 수

// // 계정 생성
// const accounts = createAccounts(numAccounts);

// // 새 계정에 이더를 할당
// fundAccounts(accounts).then(() => {
//     // 트랜잭션 보내기
//     sendMultipleTxs(accounts, numTxs).catch(err => {
//         console.error('트랜잭션 전송 중 오류 발생:', err.message);
//     });
// }).catch(err => {
//     console.error('이더 할당 중 오류 발생:', err.message);
// });