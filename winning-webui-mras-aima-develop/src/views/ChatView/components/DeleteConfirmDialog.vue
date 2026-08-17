<script setup lang="ts">
const model = defineModel<boolean>({ required: true });

const emit = defineEmits<{
  confirm: [];
}>();
</script>

<template>
  <v-dialog v-model="model" max-width="400">
    <v-card>
      <v-card-title class="text-headline-small">确认删除</v-card-title>
      <v-card-text>确定要删除此对话吗？删除后不可恢复。</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="model = false">取消</v-btn>
        <v-btn color="error" variant="flat" @click="emit('confirm')">删除</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
