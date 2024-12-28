const { Web3 } = require('web3');
const web3 = new Web3('http://localhost:8545'); // Besu 노드의 JSON-RPC 엔드포인트

// 보내는 계정과 개인 키
const account = '0x627306090abaB3A6e1400e9345bC60c78a8BEf57';
const privateKey = 'c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3';

// 트랜잭션 객체 생성
//from: ether를 보내는 계정 주소
//to: ether를 받는 계정 주소 
//value: 전송할 ether의 양
//gas: 트랜잭션에 소모될 가스의 한도
//chain ID : 블록체인 식별 id => genesis 파일에 있는 id
//gasPrice: 가스 가격
//이 객체의 경우 1 wei(가장 작은 이더 단위)를 전송하는 트랜잭션임
//0.000000000000000001 ETH 가 1 wei임
const tx = {
    from: account,
    to: '0xf17f52151EbEF6C7334FAD080c5704D77216b732',
    value: web3.utils.toWei('1', 'wei'),
    gas: 21000,
    gasPrice: 0,
    chainId: 1337
};

// 트랜잭션 서명 및 전송
async function sendTx() {
    try {
        const signedTx = await web3.eth.accounts.signTransaction(tx, privateKey);
        const receipt = await web3.eth.sendSignedTransaction(signedTx.rawTransaction);
        console.log('트랜잭션 영수증:', receipt);
    } catch (error) {
        console.error('트랜잭션 전송 오류:', error);
    }
}

sendTx();
