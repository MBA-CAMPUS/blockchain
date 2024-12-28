'use strict';

const { WorkloadModuleInterface } = require('@hyperledger/caliper-core');

class MyWorkload extends WorkloadModuleInterface {
    constructor() {
        super();
        // 필요한 경우 추가적인 초기화 작업
    }

    async initializeWorkloadModule(workerIndex, totalWorkers, roundIndex, roundArguments, sutAdapter, sutContext) {
        this.workerIndex = workerIndex;
        this.totalWorkers = totalWorkers;
        this.roundIndex = roundIndex;
        this.roundArguments = roundArguments;
        this.sutAdapter = sutAdapter;
        this.sutContext = sutContext;
        // 필요한 경우 추가적인 초기 설정

        // 계정 정보를 설정합니다 (예시로)
        this.fromAddress = '0x627306090abaB3A6e1400e9345bC60c78a8BEf57'; // 실제 송신자 주소로 변경
        this.toAddress = '0xf17f52151EbEF6C7334FAD080c5704D77216b732'; // 실제 수신자 주소로 변경
    }

    async submitTransaction() {
        // Ether 전송을 위한 트랜잭션 구성
        const txArgs = {
            from: this.fromAddress,
            to: this.toAddress,
            value: '0x1', // 전송할 Ether 양 (Wei 단위, 여기서는 1 wei)
            gas: 21000, // 기본 가스 한도
            gasPrice: 0 // 가스 가격 (Wei 단위)
        };

        return this.sutAdapter.sendTransaction(txArgs);
    }

    async cleanupWorkloadModule() {
        // 필요한 경우 리소스 정리 작업
    }
}

function createWorkloadModule() {
    return new MyWorkload();
}

module.exports.createWorkloadModule = createWorkloadModule;










